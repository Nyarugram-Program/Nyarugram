package org.telegram.botgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.QuickAckDelegate;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Generic TL-object transport. All chat semantics and identity mapping belong to the backend. */
public final class BotgramTransport {
    private static final ConcurrentHashMap<Integer, BotgramTransport> instances = new ConcurrentHashMap<>();
    private static final ExecutorService logouts = Executors.newCachedThreadPool();
    public static BotgramTransport get(int account) { return instances.computeIfAbsent(account, BotgramTransport::new); }
    private final int account;
    private final SharedPreferences binding;
    // Preserve the original stage queue's send order (in particular InvokeAfter sends).
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    // Upload parts and download ranges run on their own pools: a 64 KiB part must never
    // queue behind a chat RPC, and uploads must not starve image/video downloads.
    private final ExecutorService mediaUploads = Executors.newFixedThreadPool(4);
    private final ExecutorService mediaDownloads = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<Integer, Job> pending = new ConcurrentHashMap<>();
    private volatile BotgramSession session;
    private volatile BotgramProtobufIdentity identities;
    private volatile BotgramSession identitySession;
    private BotgramApi sessionApi;
    private BotgramSession apiSession;
    private volatile BotgramSessionScope scope = new BotgramSessionScope();
    private volatile BotgramPremiumState premium = new BotgramPremiumState();
    private BotgramSession premiumLoadedSession;
    private BotgramSession premiumRefreshingSession;
    private long premiumNextRetry;
    private boolean resetting;
    private final ArrayList<Runnable> ready = new ArrayList<>();

    BotgramPremiumState premiumState() {
        return premium;
    }

    public Runnable bindRequest(Runnable task) {
        return enabled() ? scope.bind(task) : task;
    }

    /** Stable REST identity of the bound account; never a TL id or GID. */
    public String boundUserId() {
        BotgramSession value = session;
        return value == null || value.userId == null ? "" : value.userId;
    }

    public String boundServer() {
        BotgramSession value = session;
        return value == null || value.server == null ? "" : value.server;
    }

    public boolean hasActiveSession() {
        BotgramSession value = session;
        return value != null && value.token != null && !expired;
    }

    public interface AccountRequest<T> {
        T run(BotgramApi api) throws Exception;
    }

    public interface WallpaperRequest<T> {
        T run(BotgramApi api, BotgramProtobufIdentity identities) throws Exception;
    }

    public <T> void wallpaperRequest(WallpaperRequest<T> operation,
                                     java.util.function.BiConsumer<T, TLRPC.TL_error> callback) {
        typedRequest(operation, callback);
    }

    public <T> void typedRequest(WallpaperRequest<T> operation,
                                 java.util.function.BiConsumer<T, TLRPC.TL_error> callback) {
        final BotgramSession credentials = session;
        final BotgramSessionScope owner = scope;
        accountRequest(api -> {
            String protocol = credentials.protocol();
            if (protocol == null) {
                protocol = api.negotiate();
                if (session != credentials || scope != owner) throw new BotgramApi.ApiError(401, "SESSION_EXPIRED");
                credentials.pinProtocol(protocol);
            }
            if (!"protobuf-v1".equals(protocol)) throw new BotgramApi.ApiError(501, "BOTGRAM_WALLPAPER_PROTOCOL_UNSUPPORTED");
            BotgramProtobufIdentity identity = identities;
            if (identity == null || identitySession != credentials) {
                identity = new BotgramProtobufIdentity(api, credentials.userId,
                        () -> session == credentials && scope == owner && !expired, premium);
                identities = identity;
                identitySession = credentials;
            }
            identity.initialize();
            return operation.run(api, identity);
        }, () -> true, callback);
    }

    void wallpaperChanged() {
        if (enabled() && !expired) wallpapers.changed();
    }

    public <T> void accountRequest(AccountRequest<T> operation, java.util.function.BooleanSupplier active,
                                   java.util.function.BiConsumer<T, TLRPC.TL_error> callback) {
        final BotgramSession credentials = session;
        final BotgramSessionScope owner = scope;
        requests.execute(() -> {
            if (session != credentials || scope != owner || !active.getAsBoolean()) return;
            T value = null;
            TLRPC.TL_error failure = null;
            try {
                if (!enabled() || expired || credentials.token == null)
                    throw new BotgramApi.ApiError(401, "SESSION_EXPIRED");
                value = operation.run(new BotgramApi(credentials.server, credentials.token, credentials.largeClientHello));
            } catch (BotgramApi.ApiError error) {
                failure = error(error.status, error.getMessage());
                if (error.status == 401) expire(credentials);
            } catch (Exception error) {
                org.telegram.messenger.FileLog.e(error);
                failure = error(500, "BOTGRAM_TRANSPORT_ERROR");
            }
            final T result = value;
            final TLRPC.TL_error resultError = failure;
            AndroidUtilities.runOnUIThread(owner.bind(() -> {
                if (session == credentials && scope == owner && !expired && active.getAsBoolean())
                    callback.accept(result, resultError);
            }));
        });
    }

    public void premiumStatus(boolean activate, java.util.function.BooleanSupplier active,
                              java.util.function.BiConsumer<Boolean, TLRPC.TL_error> callback) {
        final long userId = UserConfig.getInstance(account).getClientUserId();
        final BotgramPremiumState confirmed = premium;
        accountRequest(api -> {
            if (userId == 0) throw new BotgramApi.ApiError(502, "BOTGRAM_PREMIUM_RESPONSE_INVALID");
            org.telegram.botgram.protocol.v1.BotgramRpc.RpcResponse result = api.authRpc(
                    BotgramApi.request().setPremium(
                            org.telegram.botgram.protocol.v1.BotgramRpc.PremiumRequest.newBuilder()
                                    .setActivate(activate)).build(), true);
            if (!result.hasPremium()) throw new BotgramApi.ApiError(502, "BOTGRAM_PREMIUM_RESPONSE_INVALID");
            boolean enabled = result.getPremium().getActive();
            if (activate && !enabled) throw new BotgramApi.ApiError(502, "BOTGRAM_PREMIUM_NOT_ACTIVATED");
            confirmed.resolve(userId, enabled);
            return enabled;
        }, () -> true, (enabled, failure) -> {
            TLRPC.TL_error resultError = failure;
            if (failure == null) {
                TLRPC.User current = UserConfig.getInstance(account).getCurrentUser();
                if (current == null || current.id != userId) {
                    resultError = error(409, "BOTGRAM_PREMIUM_USER_CHANGED");
                } else {
                    org.telegram.tgnet.SerializedData bytes = new org.telegram.tgnet.SerializedData();
                    current.serializeToStream(bytes);
                    org.telegram.tgnet.SerializedData input = new org.telegram.tgnet.SerializedData(bytes.toByteArray());
                    TLRPC.User updated = TLRPC.User.TLdeserialize(input, input.readInt32(true), true);
                    input.cleanup();
                    bytes.cleanup();
                    updated.premium = enabled;
                    updated.flags = enabled ? updated.flags | (1 << 28) : updated.flags & ~(1 << 28);
                    UserConfig.getInstance(account).setCurrentUser(updated);
                    UserConfig.getInstance(account).saveConfig(true);
                    MessagesController.getInstance(account).putUser(updated, false);
                    ArrayList<TLRPC.User> users = new ArrayList<>();
                    users.add(updated);
                    org.telegram.messenger.MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,
                            MessagesController.UPDATE_MASK_ALL);
                }
            }
            if (active.getAsBoolean()) callback.accept(enabled, resultError);
            else if (resultError != null) org.telegram.messenger.FileLog.e("Nyarugram Premium request failed: " + resultError.text);
        });
    }

    public void whenReady(Runnable task) {
        if (resetting) ready.add(task);
        else task.run();
    }

    public void logout(boolean revoke, Runnable cleanup) {
        if (resetting) return;
        resetting = true;
        logout(revoke);
        scope.close(() -> BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
            BotgramSessionScope.drain(cleanup, () -> BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
                releaseReady();
            })));
        })));
    }
    private void releaseReady() {
        resetting = false;
        ArrayList<Runnable> tasks = new ArrayList<>(ready);
        ready.clear();
        for (Runnable task : tasks) whenReady(task);
    }

    private volatile long reachable;
    private volatile boolean networkAvailable = ApplicationLoader.isNetworkOnlineRealtime();
    private int publishedState = -1;
    private boolean polling;
    private BotgramWallpaperSync wallpapers;
    private volatile boolean expired;
    private final BotgramEventSocket events = new BotgramEventSocket(new BotgramEventSocket.Listener() {
        @Override public void wake(BotgramSession credentials) {
            if (session == credentials && alertsActive()) {
                wakeDifference(true);
                BotgramSpecialCare.get(account).poll();
            }
        }
        @Override public void wallpaper(BotgramSession credentials, long revision) {
            if (session == credentials && foreground()) wallpapers.hint(revision);
        }
        @Override public void unauthorized(BotgramSession credentials) { expire(credentials); }
    });

    private boolean foreground() {
        return enabled() && !expired && session.token != null && !ApplicationLoader.mainInterfacePaused
                && UserConfig.getInstance(account).isClientActivated();
    }

    // Difference polling also runs while the UI is paused so incoming calls reach VoIPService
    // and can ring/show the call screen; read receipts and presence stay untouched.
    private boolean alertsActive() {
        return enabled() && !expired && session.token != null
                && UserConfig.getInstance(account).isClientActivated();
    }

    private void updateEvents() { events.update(session, alertsActive()); }

    private void wakeDifference(boolean socketHint) {
        final BotgramSession credentials = session;
        final long generation = events.generation();
        final BotgramSessionScope owner = scope;
        if (!alertsActive()) return;
        Utilities.stageQueue.postRunnable(owner.bind(() -> {
            if (session == credentials && events.generation() == generation && alertsActive()) {
                MessagesController.getInstance(account).getDifference();
            }
        }));
    }

    private void expire(BotgramSession credentials) {
        AndroidUtilities.runOnUIThread(() -> {
            if (session != credentials || expired) return;
            expired = true;
            BotgramSpecialCare.get(account).clear();
            events.stop();
            MessagesController.getInstance(account).performLogout(0);
        });
    }
    private final class Job {
        final BotgramSessionScope owner = scope;
        volatile boolean cancelled;
        volatile int guid;
    }

    private BotgramTransport(int account) {
        this.account = account;
        wallpapers = new BotgramWallpaperSync(account);
        binding = ApplicationLoader.applicationContext.getSharedPreferences("botgram.transport." + account, Context.MODE_PRIVATE);
        session = new BotgramSession(ApplicationLoader.applicationContext, account);
        if (enabled()) BotgramSpecialCare.get(account).bind(session);
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().addObserver(
                (id, currentAccount, args) -> {
                    updateEvents();
                    if (foreground()) wallpapers.refresh(true);
                }, NotificationCenter.activeAccountChanged));
        ((android.app.Application) ApplicationLoader.applicationContext).registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
                    private void refresh() { ApplicationLoader.applicationHandler.post(() -> {
                        updateEvents();
                        networkChanged(ApplicationLoader.isNetworkOnlineRealtime());
                        if (foreground()) wallpapers.refresh(true);
                    }); }
                    @Override public void onActivityResumed(android.app.Activity activity) { refresh(); }
                    @Override public void onActivityPaused(android.app.Activity activity) { refresh(); }
                    @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
                    @Override public void onActivityStarted(android.app.Activity activity) { }
                    @Override public void onActivityStopped(android.app.Activity activity) { refresh(); }
                    @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
                    @Override public void onActivityDestroyed(android.app.Activity activity) { }
                });
    }

    // A bound but expired account must never fall through to native Telegram transport.
    public boolean enabled() { return binding.getBoolean("enabled", false); }
    public String notificationOwner() {
        BotgramSession current = session;
        return !enabled() || expired || current.token == null || current.userId == null ? null : current.server + "\n" + current.userId;
    }
    /** Protobuf replacement for the retired JSON logout route. */
    static void logoutRpc(BotgramApi api) throws Exception {
        api.authRpc(BotgramApi.request().setAuthLogout(
                org.telegram.botgram.protocol.v1.BotgramRpc.AuthLogoutRequest.getDefaultInstance()).build(), true);
    }

    public void logout(boolean revoke) {
        BotgramSpecialCare.get(account).clear();
        BotgramMissedCallNotifications.clear(account);
        final BotgramSession credentials = session;
        final String server = credentials.server;
        final String token = credentials.token;
        expired = true;
        identities = null;
        premium = new BotgramPremiumState();
        wallpapers = new BotgramWallpaperSync(account);
        AndroidUtilities.runOnUIThread(() -> {
            if (session == credentials && expired) events.stop();
        });
        reachable = 0;
        for (Job job : pending.values()) job.cancelled = true;
        pending.clear();
        // Keep the binding: logged-out slots must not fall back to MTProto.
        // apply() clears preferences in memory now and writes them off the UI thread.
        credentials.clear();
        if (revoke && token != null) {
            // Do not queue revocation behind slow chat requests or delay local logout.
            logouts.execute(() -> {
                try { BotgramTransport.logoutRpc(new BotgramApi(server, token, credentials.largeClientHello)); }
                catch (Exception ignored) { /* Local logout also works while offline. */ }
            });
        }
    }
    public void authenticated(BotgramSession credentials, java.util.function.BooleanSupplier active, Runnable completion) {
        if (!active.getAsBoolean()) {
            Utilities.globalQueue.postRunnable(credentials::clearIfCurrent);
            return;
        }
        if (resetting) {
            whenReady(() -> authenticated(credentials, active, completion));
            return;
        }
        if (UserConfig.getInstance(account).isClientActivated()) {
            MessagesController.getInstance(account).performLogout(0);
            whenReady(() -> authenticated(credentials, active, completion));
            return;
        }
        resetting = true;
        scope.close(() -> BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
            try {
                if (!active.getAsBoolean()) {
                    Utilities.globalQueue.postRunnable(credentials::clearIfCurrent);
                    return;
                }
                activate(credentials);
                completion.run();
            } finally {
                releaseReady();
            }
        })));
    }

    private void activate(BotgramSession credentials) {
        for (Job job : pending.values()) job.cancelled = true;
        pending.clear();
        scope = new BotgramSessionScope();
        premium = new BotgramPremiumState();
        premiumLoadedSession = null;
        premiumRefreshingSession = null;
        premiumNextRetry = 0;
        wallpapers = new BotgramWallpaperSync(account);
        session = credentials;
        BotgramSpecialCare.get(account).bind(credentials);
        identities = null;
        binding.edit().putBoolean("enabled", true).apply();
        expired = false; reachable = System.currentTimeMillis();
        start();
    }
    private boolean changingTls;

    public boolean largeClientHello() { return session.largeClientHello; }

    public void setLargeClientHello(boolean enabled, java.util.function.Consumer<Boolean> completion) {
        if (changingTls) return;
        changingTls = true;
        final BotgramSession credentials = session;
        requests.execute(() -> {
            boolean saved = false;
            try {
                credentials.setLargeClientHello(enabled);
                if (session == credentials) {
                    identities = null;
                    identitySession = null;
                }
                saved = true;
            } catch (java.io.IOException ignored) { }
            final boolean success = saved;
            AndroidUtilities.runOnUIThread(() -> {
                changingTls = false;
                if (session != credentials) return;
                if (success) {
                    events.stop();
                    updateEvents();
                    reachable = 0;
                    publishState(true);
                    start();
                }
                completion.accept(success);
            });
        });
    }

    public int state() {
        if (!networkAvailable) return ConnectionsManager.ConnectionStateWaitingForNetwork;
        return !expired && reachable != 0 && System.currentTimeMillis() - reachable < 30_000
                ? ConnectionsManager.ConnectionStateConnected : ConnectionsManager.ConnectionStateConnecting;
    }
    public void networkChanged(boolean available) {
        if (networkAvailable != available) {
            reachable = 0;
            if (available && alertsActive()) BotgramSpecialCare.get(account).refresh();
        }
        networkAvailable = available;
        publishState(false);
    }
    private void publishState(boolean force) {
        BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
            if (!enabled()) return;
            int current = state();
            if (force || publishedState != current) {
                publishedState = current;
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didUpdateConnectionState);
            }
        }));
    }
    private void reachable(boolean online) {
        reachable = online && networkAvailable ? System.currentTimeMillis() : 0;
        publishState(false);
    }

    private void refreshPremiumIfNeeded() {
        final BotgramSession credentials = session;
        UserConfig config = UserConfig.getInstance(account);
        if (!enabled() || expired || credentials.token == null || !config.isClientActivated()
                || config.getCurrentUser() == null || premiumLoadedSession == credentials
                || premiumRefreshingSession == credentials
                || android.os.SystemClock.elapsedRealtime() < premiumNextRetry) return;
        premiumRefreshingSession = credentials;
        premiumStatus(false, () -> true, (value, failure) -> {
            if (premiumRefreshingSession == credentials) premiumRefreshingSession = null;
            if (failure == null) {
                premiumLoadedSession = credentials;
            } else {
                premiumNextRetry = android.os.SystemClock.elapsedRealtime() + 30_000;
                org.telegram.messenger.FileLog.e("Nyarugram Premium refresh failed: " + failure.text);
            }
        });
    }

    public void start() {
        BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
            updateEvents();
            networkChanged(ApplicationLoader.isNetworkOnlineRealtime());
            publishState(true);
            if (!enabled()) return;
            if (alertsActive()) BotgramSpecialCare.get(account).refresh();
            if (foreground()) wallpapers.refresh(true);
            refreshPremiumIfNeeded();
            if (polling) return;
            polling = true;
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override public void run() {
                    updateEvents();
                    if (!enabled()) { polling = false; return; }
                    publishState(false);
                    refreshPremiumIfNeeded();
                    wakeDifference(false);
                    if (alertsActive()) BotgramSpecialCare.get(account).poll();
                    if (foreground()) wallpapers.refresh(false);
                    AndroidUtilities.runOnUIThread(this, foreground() ? 3000 : 8000);
                }
            }, 3000);
        }));
    }

    public void send(TLObject request, RequestDelegate completion, RequestDelegateTimestamp timestamp,
                     QuickAckDelegate quickAck, int token) {
        if (expired) return;
        Job job = pending.computeIfAbsent(token, ignored -> new Job());
        final BotgramSession credentials = session;
        final String server = credentials.server;
        final String bearer = credentials.token;
        if (request instanceof TLRPC.TL_upload_saveFilePart
                || request instanceof TLRPC.TL_upload_saveBigFilePart
                || request instanceof TLRPC.TL_upload_getFile) {
            (request instanceof TLRPC.TL_upload_getFile ? mediaDownloads : mediaUploads)
                    .execute(() -> sendMedia(request, credentials, bearer, job, token, completion));
            return;
        }
        requests.execute(() -> {
            TLObject response = null; TLRPC.TL_error failure = null;
            final boolean traceCall = request instanceof TLRPC.TL_messages_getDhConfig
                    || request.getClass().getName().startsWith("org.telegram.tgnet.tl.TL_phone$");
            String stage = "session";
            try {
                if (traceCall) org.telegram.messenger.FileLog.d("BOTGRAM_CALL_RPC request=" + request.getClass().getSimpleName() + " stage=begin");
                if (job.cancelled || session != credentials || scope != job.owner) return;
                if (bearer == null) throw new BotgramApi.ApiError(401, "SESSION_EXPIRED");
                if (BotgramGiftPolicy.isDisabled() && BotgramGiftPolicy.isGiftRequest(request)) {
                    if (!(request instanceof TLRPC.TL_payments_assignPlayMarketTransaction)) {
                        AndroidUtilities.runOnUIThread(job.owner.bind(() -> {
                            if (!job.cancelled && session == credentials && scope == job.owner && !expired) {
                                BotgramGiftPolicy.notifyBlocked(account);
                            }
                        }));
                    }
                    throw new BotgramApi.ApiError(403, "NYARUGRAM_GIFTS_UNAVAILABLE");
                }
                if (sessionApi == null || apiSession != credentials
                        || sessionApi.usesLargeClientHello() != credentials.largeClientHello) {
                    sessionApi = new BotgramApi(server, bearer, credentials.largeClientHello);
                    apiSession = credentials;
                }
                BotgramApi api = sessionApi;
                if (request instanceof TLRPC.TL_auth_logOut) {
                    logoutRpc(api);
                    response = new TLRPC.TL_auth_loggedOut();
                    if (session == credentials) {
                        expired = true;
                        credentials.clear();
                        AndroidUtilities.runOnUIThread(() -> {
                            if (session == credentials && expired) events.stop();
                        });
                    }
                } else {
                    if (job.owner != scope || session != credentials) return;
                    String protocol = credentials.protocol();
                    stage = "negotiation";
                    if (protocol == null) {
                        protocol = api.negotiate();
                        if (job.cancelled || session != credentials || scope != job.owner) return;
                        credentials.pinProtocol(protocol);
                    }
                    if (job.cancelled || session != credentials || scope != job.owner) return;
                    // A pinned legacy protocol can never fall back to the retired
                    // JSON RPC; re-negotiation is required instead of silent JSON.
                    if (!"protobuf-v1".equals(protocol)) {
                        throw new BotgramApi.ApiError(410, BotgramApi.JSON_API_RETIRED);
                    }
                    {
                        BotgramProtobufIdentity identity = identities;
                        if (identity == null || identitySession != credentials) {
                            identity = new BotgramProtobufIdentity(api, credentials.userId,
                                    () -> session == credentials && scope == job.owner && !expired, premium);
                            if (session == credentials && scope == job.owner) {
                                identities = identity;
                                identitySession = credentials;
                            }
                        }
                        stage = "identity";
                        identity.initialize();
                        stage = "request_mapping";
                        org.telegram.botgram.protocol.v1.BotgramRpc.RpcRequest typed =
                                new BotgramProtobufRequestMapper(identity).map(request, java.util.UUID.randomUUID().toString())
                                        .toBuilder().setForeground(foreground() && UserConfig.selectedAccount == account).build();
                        if (job.cancelled || session != credentials || scope != job.owner) return;
                        stage = "http";
                        org.telegram.botgram.protocol.v1.BotgramRpc.RpcResponse result = api.rpc(typed);
                        if (job.cancelled || session != credentials || scope != job.owner) return;
                        stage = "response_mapping";
                        response = new BotgramProtobufResponseMapper(identity).map(result);
                    }
                }
                if (!job.cancelled && session == credentials) reachable(true);
                if (traceCall) org.telegram.messenger.FileLog.d("BOTGRAM_CALL_RPC request=" + request.getClass().getSimpleName() + " stage=success");
            } catch (BotgramApi.ApiError error) {
                if (traceCall) {
                    String code = error.getMessage();
                    // Record symbolic errors only, never arbitrary server text or request payloads.
                    if (code == null || code.length() > 200 || !code.matches("[A-Za-z0-9_:.-]+")) code = "REDACTED";
                    org.telegram.messenger.FileLog.e("BOTGRAM_CALL_RPC request=" + request.getClass().getSimpleName()
                            + " stage=" + stage + " status=" + error.status + " error=" + code);
                }
                if (!job.cancelled && session == credentials) reachable(error.status != 495);
                failure = error(error.status, error.getMessage());
                if (error.status == 401 && !job.cancelled && session == credentials) expire(credentials);
            } catch (Exception error) {
                if (traceCall) org.telegram.messenger.FileLog.e("BOTGRAM_CALL_RPC request=" + request.getClass().getSimpleName()
                        + " stage=" + stage + " exception=" + error.getClass().getName());
                if (!job.cancelled && session == credentials) reachable(false);
                failure = error(500, "BOTGRAM_TRANSPORT_ERROR");
            }
            finally { finish(token, job, completion, timestamp, response, failure); }
        });
    }

    /**
     * Binary media transport. Upload parts and download ranges use the dedicated HTTP media
     * plane instead of the Protobuf RPC envelope; every callback keeps the same session,
     * cancellation and expiry semantics as the RPC path.
     */
    private void sendMedia(TLObject request, BotgramSession credentials, String bearer, Job job, int token,
                           RequestDelegate completion) {
        TLObject response = null;
        TLRPC.TL_error failure = null;
        try {
            if (job.cancelled || session != credentials || !enabled() || expired) return;
            if (bearer == null) throw new BotgramApi.ApiError(401, "SESSION_EXPIRED");
            BotgramApi api = new BotgramApi(credentials.server, bearer, credentials.largeClientHello);
            if (request instanceof TLRPC.TL_upload_saveFilePart) {
                TLRPC.TL_upload_saveFilePart value = (TLRPC.TL_upload_saveFilePart) request;
                api.uploadPart(value.file_id, value.file_part, 0, bufferBytes(value.bytes));
                response = new TLRPC.TL_boolTrue();
            } else if (request instanceof TLRPC.TL_upload_saveBigFilePart) {
                TLRPC.TL_upload_saveBigFilePart value = (TLRPC.TL_upload_saveBigFilePart) request;
                api.uploadPart(value.file_id, value.file_part, value.file_total_parts,
                        bufferBytes(value.bytes));
                response = new TLRPC.TL_boolTrue();
            } else {
                TLRPC.TL_upload_getFile value = (TLRPC.TL_upload_getFile) request;
                BotgramApi.MediaRange range = api.downloadRange(mediaId(value.location), value.offset, value.limit);
                TLRPC.TL_upload_file file = new TLRPC.TL_upload_file();
                file.type = new TLRPC.TL_storage_fileUnknown();
                file.mtime = (int) (System.currentTimeMillis() / 1000);
                file.bytes = nativeBuffer(range.bytes);
                response = file;
            }
            if (!job.cancelled && session == credentials) reachable(true);
        } catch (BotgramApi.ApiError error) {
            if (!job.cancelled && session == credentials) reachable(error.status != 495);
            failure = error(error.status, error.getMessage());
            if (error.status == 401 && !job.cancelled && session == credentials) expire(credentials);
        } catch (Exception error) {
            org.telegram.messenger.FileLog.e(error);
            if (!job.cancelled && session == credentials) reachable(false);
            failure = error(500, "BOTGRAM_MEDIA_TRANSPORT_ERROR");
        } finally { finish(token, job, completion, null, response, failure); }
    }

    private static byte[] bufferBytes(NativeByteBuffer value) {
        if (value == null) return new byte[0];
        java.nio.ByteBuffer source = value.buffer.duplicate();
        source.rewind();
        byte[] data = new byte[source.remaining()];
        source.get(data);
        return data;
    }

    private static NativeByteBuffer nativeBuffer(byte[] data) throws Exception {
        NativeByteBuffer buffer = new NativeByteBuffer(data.length);
        buffer.buffer.put(data);
        buffer.buffer.position(0);
        return buffer;
    }

    // Photo and document locations carry the media id; legacy file locations encode it in
    // the two 32-bit halves the server assigned when the media was created.
    private static long mediaId(TLRPC.InputFileLocation location) throws BotgramApi.ApiError {
        if (location instanceof TLRPC.TL_inputPhotoFileLocation)
            return ((TLRPC.TL_inputPhotoFileLocation) location).id;
        if (location instanceof TLRPC.TL_inputDocumentFileLocation)
            return ((TLRPC.TL_inputDocumentFileLocation) location).id;
        if (location instanceof TLRPC.TL_inputFileLocation) {
            TLRPC.TL_inputFileLocation value = (TLRPC.TL_inputFileLocation) location;
            return (value.volume_id & 0xffffffffL) | ((long) value.local_id << 32);
        }
        throw new BotgramApi.ApiError(501, "BOTGRAM_MEDIA_LOCATION_UNSUPPORTED");
    }

    private void finish(int token, Job job, RequestDelegate completion, RequestDelegateTimestamp timestamp, TLObject response, TLRPC.TL_error failure) {
        Utilities.stageQueue.postRunnable(job.owner.bind(() -> {
            pending.remove(token, job);
            if (job.cancelled) return;
            if (completion != null) completion.run(response, failure);
            else if (timestamp != null) timestamp.run(response, failure, (int)(System.currentTimeMillis() / 1000));
            else if (response instanceof TLRPC.Updates) MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
        }));
    }
    private static TLRPC.TL_error error(int code, String text) {
        TLRPC.TL_error result = new TLRPC.TL_error(); result.code = code; result.text = text; return result;
    }
    public void cancel(int token) { pending.computeIfAbsent(token, ignored -> new Job()).cancelled = true; }
    public void bind(int token, int guid) { if (guid != 0) pending.computeIfAbsent(token, ignored -> new Job()).guid = guid; }
    public void cancelGuid(int guid) { for (Job job : pending.values()) if (job.guid == guid) job.cancelled = true; }

    public static Object encode(Object value, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("TL nesting limit");
        if (value == null) return JSONObject.NULL;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof byte[]) return new JSONObject().put("$bytes", Base64.encodeToString((byte[])value, Base64.NO_WRAP));
        if (value instanceof Iterable) {
            JSONArray array = new JSONArray(); for (Object item : (Iterable<?>)value) array.put(encode(item, depth + 1)); return array;
        }
        if (!(value instanceof TLObject)) return JSONObject.NULL;
        JSONObject result = new JSONObject().put("$type", value.getClass().getName());
        for (Field field : value.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Object member = field.get(value);
            if (member != null) result.put(field.getName(), encode(member, depth + 1));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object decode(Object value, Class<?> expected, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("TL nesting limit");
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            if (object.has("$bytes")) return Base64.decode(object.getString("$bytes"), Base64.NO_WRAP);
            String name = object.getString("$type");
            if (!name.startsWith("org.telegram.tgnet.")) throw new IllegalArgumentException("Unknown TL namespace");
            Class<?> type = Class.forName(name);
            if (!TLObject.class.isAssignableFrom(type) || !expected.isAssignableFrom(type)) throw new IllegalArgumentException("Unexpected TL type");
            Object result = type == Vector.class ? new Vector<>(null) : type.getDeclaredConstructor().newInstance();
            for (Field field : type.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !object.has(field.getName()) || object.isNull(field.getName())) continue;
                Object item = object.get(field.getName());
                if (item instanceof JSONArray && List.class.isAssignableFrom(field.getType())) {
                    List items = (List) field.get(result);
                    if (items == null) { items = new ArrayList<>(); field.set(result, items); }
                    items.clear();
                    Class<?> element = Object.class;
                    if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType) {
                        java.lang.reflect.Type argument = ((java.lang.reflect.ParameterizedType)field.getGenericType()).getActualTypeArguments()[0];
                        if (argument instanceof Class) element = (Class<?>)argument;
                    }
                    JSONArray array = (JSONArray)item;
                    for (int i = 0; i < array.length(); i++) items.add(decode(array.get(i), element, depth + 1));
                } else field.set(result, decode(item, field.getType(), depth + 1));
            }
            return result;
        }
        if (value instanceof Number) {
            Number number = (Number)value;
            if (expected == int.class || expected == Integer.class) return Math.toIntExact(number.longValue());
            if (expected == long.class || expected == Long.class) return number.longValue();
            if (expected == double.class || expected == Double.class) return number.doubleValue();
        }
        return value;
    }
}
