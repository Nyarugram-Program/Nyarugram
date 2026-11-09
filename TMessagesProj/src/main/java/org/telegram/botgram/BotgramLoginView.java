package org.telegram.botgram;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.InputFilter;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.SlideView;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Credentials slide inside LoginActivity's original layout and navigation. */
public final class BotgramLoginView extends SlideView {
    public interface Completion { void done(TLRPC.User user); }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BotgramRegistrationLocation location;
    private final ArrayList<OutlineTextContainerView> outlines = new ArrayList<>();
    private final ArrayList<EditTextBoldCursor> fields = new ArrayList<>();
    private final LinearLayout form;
    private final TextView title, subtitle, switchMode;
    private boolean largeClientHello;
    private final EditTextBoldCursor server, port, username, displayName, password, additionalPassword;
    private final Completion completion;
    private final Consumer<Boolean> loading;
    private final Consumer<String> showError;
    private final int account;
    private volatile boolean disposed;
    private volatile int attempt;
    private boolean registering, busy, modeAnimating, splittingAddress;
    private Future<?> request;

    public BotgramLoginView(Context context, Activity activity, int account, Completion completion,
                            Consumer<Boolean> loading, Consumer<String> showError) {
        super(context);
        this.account = account;
        this.completion = completion;
        this.loading = loading;
        this.showError = showError;
        location = new BotgramRegistrationLocation(activity);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        // Keep the fields reachable on small screens and above the system keyboard.
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        form = new LinearLayout(context);
        form.setOrientation(VERTICAL);
        form.setPadding(0, AndroidUtilities.dp(32), 0, AndroidUtilities.dp(80));
        scroll.addView(form);
        title = label(context, 18, true);
        form.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));
        subtitle = label(context, 14, false);
        form.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 8, 16, 20));
        LinearLayout endpointRow = new LinearLayout(context);
        endpointRow.setOrientation(HORIZONTAL);
        endpointRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        form.addView(endpointRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 62, 0, 0, 0, 10));
        server = field(context, R.string.BotgramServer, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, endpointRow);
        port = field(context, R.string.BotgramPort, InputType.TYPE_CLASS_NUMBER, endpointRow);
        endpointRow.getChildAt(0).setLayoutParams(LayoutHelper.createLinear(0, 62, 1f));
        endpointRow.getChildAt(1).setLayoutParams(LayoutHelper.createLinear(96, 62, 10, 0, 0, 0));
        port.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        server.setTextDirection(View.TEXT_DIRECTION_LTR);
        port.setTextDirection(View.TEXT_DIRECTION_LTR);
        String savedServer = context.getSharedPreferences("botgram.session." + account, Context.MODE_PRIVATE)
                .getString("server", "");
        try {
            BotgramServerAddress address = BotgramServerAddress.parse(BotgramServerAddress.migrateLegacy(savedServer));
            server.setText(address.host);
            port.setText(address.port);
        } catch (IllegalArgumentException error) {
            server.setText(savedServer);
            port.setText("");
        }
        server.addTextChangedListener(new TextWatcher() {
            private int changedCount;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changedCount = count; }
            @Override public void afterTextChanged(Editable text) {
                if (splittingAddress || changedCount <= 1) return;
                try {
                    String input = text.toString().trim();
                    BotgramServerAddress address = BotgramServerAddress.parse(input);
                    // Leave host-only edits alone. Split an explicitly entered/pasted port.
                    if (!input.equals(address.host) && input.matches(".*:[0-9]+/?$")) {
                        splittingAddress = true;
                        server.setText(address.host);
                        server.setSelection(server.length());
                        port.setText(address.port);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Partial input is validated on submit, without interrupting typing.
                } finally { splittingAddress = false; }
            }
        });
        username = field(context, R.string.Username, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        displayName = field(context, R.string.BotgramDisplayName, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        password = field(context, R.string.LoginPassword, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        password.setTypeface(Typeface.DEFAULT);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            onNextPressed(null);
            return true;
        });
        password.setSaveEnabled(false);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        additionalPassword = field(context, R.string.BotgramAuthenticatorCode, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        additionalPassword.setTransformationMethod(PasswordTransformationMethod.getInstance());
        additionalPassword.setTypeface(Typeface.DEFAULT);
        additionalPassword.setSaveEnabled(false);
        additionalPassword.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        additionalPassword.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        additionalPassword.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            onNextPressed(null);
            return true;
        });
        ((View) additionalPassword.getParent()).setVisibility(GONE);
        switchMode = label(context, 15, false);
        switchMode.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16));
        form.addView(switchMode, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        switchMode.setOnClickListener(view -> {
            if (busy || modeAnimating) return;
            changeMode(!registering);
        });
        updateMode();
        updateColors();
    }

    public org.telegram.ui.ActionBar.BaseFragment connectionSettings() {
        if (disposed || busy || modeAnimating) return null;
        AndroidUtilities.hideKeyboard(this);
        return new BotgramConnectionSettingsActivity(largeClientHello, enabled -> {
            if (!disposed && !busy) largeClientHello = enabled;
        });
    }

    private TextView label(Context context, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(AndroidUtilities.dp(2), 1f);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private EditTextBoldCursor field(Context context, int label, int inputType) {
        return field(context, label, inputType, form);
    }

    private EditTextBoldCursor field(Context context, int label, int inputType, LinearLayout parent) {
        OutlineTextContainerView outline = new OutlineTextContainerView(context);
        outline.setText(LocaleController.getString(label));
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(18);
        field.setSingleLine(true);
        field.setBackground(null);
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setInputType(inputType);
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        int padding = AndroidUtilities.dp(16);
        field.setPadding(padding, padding, padding, padding);
        field.setOnFocusChangeListener((view, focused) -> outline.animateSelection(focused ? 1f : 0f));
        outline.attachEditText(field);
        outline.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP));
        outlines.add(outline);
        fields.add(field);
        parent.addView(outline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 62, Gravity.CENTER_HORIZONTAL, 0, 0, 0, parent == form ? 10 : 0));
        return field;
    }

    private void changeMode(boolean register) {
        if (registering == register || disposed) return;
        if (!register && displayName.hasFocus()) password.requestFocus();
        registering = register;
        additionalPassword.setText("");
        ((View) additionalPassword.getParent()).setVisibility(GONE);
        if (!isLaidOut()) { updateMode(); return; }
        modeAnimating = true;
        switchMode.setEnabled(false);
        // Swap layout while faded out, so the extra registration field never jumps on screen.
        form.animate().alpha(0f).translationY(-AndroidUtilities.dp(8)).setDuration(100)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(() -> {
                    if (disposed) return;
                    updateMode();
                    form.setTranslationY(AndroidUtilities.dp(8));
                    form.animate().alpha(1f).translationY(0f).setDuration(220)
                            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(() -> {
                                modeAnimating = false;
                                switchMode.setEnabled(!busy);
                            }).start();
                }).start();
    }

    private void updateMode() {
        title.setText(LocaleController.getString(registering ? R.string.BotgramSignUp : R.string.BotgramSignIn));
        subtitle.setText(LocaleController.getString(registering ? R.string.BotgramSignUpInfo : R.string.BotgramSignInInfo));
        ((View) displayName.getParent()).setVisibility(registering ? VISIBLE : GONE);
        switchMode.setText(LocaleController.getString(registering ? R.string.BotgramHaveAccount : R.string.BotgramCreateAccount));
    }

    @Override public void onNextPressed(String code) {
        if (busy || modeAnimating || disposed) return;
        for (EditTextBoldCursor field : fields) {
            if (field == port) continue;
            if (field == displayName && !registering) continue;
            if (field == additionalPassword && ((View) field.getParent()).getVisibility() != VISIBLE) continue;
            if (field.getText().toString().trim().isEmpty()) {
                field.requestFocus();
                AndroidUtilities.shakeView(field);
                AndroidUtilities.showKeyboard(field);
                return;
            }
        }
        final String address;
        try {
            String portText = port.getText().toString().trim();
            if (!portText.isEmpty() && (!portText.matches("[0-9]{1,5}") || Integer.parseInt(portText) < 1 || Integer.parseInt(portText) > 65535)) {
                port.requestFocus();
                port.setError(LocaleController.getString(R.string.BotgramInvalidPort));
                return;
            }
            address = BotgramServerAddress.join(server.getText().toString(), portText);
        } catch (IllegalArgumentException error) {
            server.requestFocus();
            server.setError(LocaleController.getString(R.string.BotgramInvalidServer));
            return;
        }
        final String name = username.getText().toString().trim();
        final boolean useLargeClientHello = largeClientHello;
        if (useLargeClientHello && !address.startsWith("https://")) {
            showError.accept(LocaleController.getString(R.string.BotgramLargeClientHelloHttpsRequired));
            return;
        }
        final String secret = password.getText().toString(), display = displayName.getText().toString().trim();
        final boolean register = registering;
        final boolean needsAdditionalPassword = !register && ((View) additionalPassword.getParent()).getVisibility() == VISIBLE;
        final String secondSecret = additionalPassword.getText().toString();
        final int id = ++attempt;
        setBusy(true);
        Consumer<String> authenticate = country -> {
            if (disposed || id != attempt) return;
            request = worker.submit(() -> {
                int failureStage = R.string.BotgramLoginRequestFailed;
                try {
                    BotgramApi api = new BotgramApi(address, null, useLargeClientHello);
                    org.telegram.botgram.protocol.v1.BotgramRpc.RpcRequest.Builder builder = BotgramApi.request();
                    if (register) {
                        org.telegram.botgram.protocol.v1.BotgramRpc.AuthRegisterRequest.Builder registration =
                                org.telegram.botgram.protocol.v1.BotgramRpc.AuthRegisterRequest.newBuilder()
                                        .setUsername(name).setPassword(secret).setDisplayName(display);
                        if (country != null) registration.setLocationCountry(country);
                        builder.setAuthRegister(registration);
                    } else {
                        org.telegram.botgram.protocol.v1.BotgramRpc.AuthLoginRequest.Builder login =
                                org.telegram.botgram.protocol.v1.BotgramRpc.AuthLoginRequest.newBuilder()
                                        .setUsername(name).setPassword(secret);
                        if (needsAdditionalPassword) login.setTotpCode(secondSecret.trim());
                        builder.setAuthLogin(login);
                    }
                    org.telegram.botgram.protocol.v1.BotgramRpc.RpcResponse response = api.authRpc(builder.build(), false);
                    failureStage = R.string.BotgramLoginResponseFailed;
                    org.telegram.botgram.protocol.v1.BotgramRpc.AuthSession session =
                            register ? response.getAuthRegister() : response.getAuthLogin();
                    TLRPC.User user = BotgramTlIdentity.user(session.getUser());
                    if (disposed || id != attempt) return;
                    failureStage = R.string.BotgramLoginSessionFailed;
                    BotgramSession saved = new BotgramSession(getContext().getApplicationContext(), account);
                    if (!saved.save(api.baseUrl, session.getToken(), session.getUser().getUserId(),
                            session.getUser().getDisplayName(), useLargeClientHello, () -> !disposed && id == attempt)) return;
                    AndroidUtilities.runOnUIThread(() -> BotgramTransport.get(account).whenReady(() -> {
                        if (disposed || id != attempt) {
                            Utilities.globalQueue.postRunnable(saved::clearIfCurrent);
                            return;
                        }
                        BotgramTransport.get(account).authenticated(saved, () -> !disposed && id == attempt, () -> {
                            if (disposed || id != attempt) return;
                            password.setText("");
                            additionalPassword.setText("");
                            setBusy(false);
                            completion.done(user);
                        });
                    }));
                } catch (Exception error) {
                    final int failedStage = failureStage;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (disposed || id != attempt) return;
                        setBusy(false);
                        if ("TOTP_REQUIRED".equals(error.getMessage())) {
                            ((View) additionalPassword.getParent()).setVisibility(VISIBLE);
                            additionalPassword.requestFocus();
                            AndroidUtilities.showKeyboard(additionalPassword);
                            return;
                        }
                        showError.accept(errorText(error, failedStage));
                    });
                }
            });
        };
        if (register) {
            subtitle.setText(LocaleController.getString(R.string.BotgramLocatingRegion));
            location.resolve(authenticate);
        } else authenticate.accept(null);
    }

    private String errorText(Exception error, int failureStage) {
        if (error instanceof BotgramApi.ApiError) {
            if (((BotgramApi.ApiError) error).status == 410 && BotgramApi.JSON_API_RETIRED.equals(error.getMessage()))
                return BotgramApi.JSON_API_RETIRED;
            if ("TOTP_CODE_INVALID".equals(error.getMessage())) return LocaleController.getString(R.string.BotgramAuthenticatorInvalid);
            if ("TOTP_TOO_MANY_ATTEMPTS".equals(error.getMessage())) return LocaleController.getString(R.string.BotgramAuthenticatorWait);
            if ("PASSWORD_HASH_INVALID".equals(error.getMessage())) return LocaleController.getString(R.string.CheckPasswordWrong);
            if ("invalid_credentials".equals(error.getMessage())) return LocaleController.getString(R.string.BotgramInvalidCredentials);
            if ("username_taken".equals(error.getMessage())) return LocaleController.getString(R.string.BotgramUsernameTaken);
            if ("BOTGRAM_TLS_PEER_UNVERIFIED".equals(error.getMessage())
                    || "BOTGRAM_TLS_HANDSHAKE_FAILED".equals(error.getMessage())) {
                return LocaleController.formatString(R.string.BotgramLoginRequestFailed,
                        error.getMessage() + " / " + BotgramTlsDiagnostics.describe(error.getCause()));
            }
            return LocaleController.getString(R.string.BotgramAuthenticationFailed);
        }
        if (error instanceof IllegalArgumentException && failureStage == R.string.BotgramLoginRequestFailed)
            return LocaleController.getString(R.string.BotgramInvalidServer);
        // Keep response/account details private. Socket diagnostics use only known OS error codes.
        String reason = error.getClass().getSimpleName();
        if (error.getCause() != null) reason += " / " + error.getCause().getClass().getSimpleName();
        if (error instanceof java.net.SocketException) {
            if (error.getCause() instanceof android.system.ErrnoException) {
                reason += " / errno=" + ((android.system.ErrnoException) error.getCause()).errno;
            } else if (error.getMessage() != null) {
                java.util.regex.Matcher socketReason = java.util.regex.Pattern.compile(
                        "\\b(EPERM|EACCES|ECONNRESET|ECONNREFUSED|ENETUNREACH|EHOSTUNREACH|EBADF|ENOTCONN|ETIMEDOUT|EPIPE|"
                                + "connection reset|socket closed|connection refused|network is unreachable|software caused connection abort)\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(error.getMessage());
                if (socketReason.find()) reason += " / " + socketReason.group();
            }
        }
        return LocaleController.formatString(failureStage, reason);
    }

    private void setBusy(boolean value) {
        busy = value;
        for (EditTextBoldCursor field : fields) field.setEnabled(!value);
        switchMode.setEnabled(!value);
        if (!value) updateMode();
        loading.accept(value);
    }

    @Override public void onCancelPressed() {
        ++attempt;
        location.cancel();
        if (request != null) request.cancel(true);
        if (!disposed) setBusy(false);
    }

    @Override public boolean onBackPressed(boolean invoked) {
        if (modeAnimating) return false;
        if (busy) { if (invoked) onCancelPressed(); return false; }
        if (registering) {
            if (invoked) changeMode(false);
            return false;
        }
        return true;
    }

    public void dispose() {
        disposed = true;
        form.animate().cancel();
        ++attempt;
        location.dispose();
        worker.shutdownNow();
    }
    @Override public void onDestroyActivity() { dispose(); }
    public void onLocationPermissionsResult(int[] results) { location.onPermissionsResult(results); }

    @Override public void updateColors() {
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        switchMode.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        switchMode.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        for (EditTextBoldCursor field : fields) {
            field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        }
        for (OutlineTextContainerView outline : outlines) outline.updateColor();
    }
}
