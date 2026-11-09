package org.telegram.botgram;

import com.google.protobuf.CodedInputStream;
import com.neovisionaries.ws.client.BotgramReceiveLimits;
import com.neovisionaries.ws.client.OpeningHandshakeException;
import com.neovisionaries.ws.client.WebSocketState;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.json.JSONObject;
import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.messenger.AndroidUtilities;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

/** Session-owned event hints, including background calls. The event cursor is never a Telegram pts. */
final class BotgramEventSocket {
    interface Listener {
        void wake(BotgramSession credentials);
        void wallpaper(BotgramSession credentials, long revision);
        void unauthorized(BotgramSession credentials);
    }

    private static final ExecutorService network = Executors.newCachedThreadPool();
    private final Listener listener;
    // State belongs to the UI thread; generation also guards work queued on the stage thread.
    private volatile long generation;
    private BotgramSession credentials;
    private String server;
    private String token;
    private long cursor;
    private boolean active;
    private boolean rejected;
    private WebSocket socket;
    private Runnable retry;
    private int backoff = 1000;

    BotgramEventSocket(Listener listener) { this.listener = listener; }

    void update(BotgramSession next, boolean receiveEvents) {
        boolean changed = credentials != next || !java.util.Objects.equals(server, next.server)
                || !java.util.Objects.equals(token, next.token);
        if (changed) {
            stop();
            credentials = next;
            server = next.server;
            token = next.token;
            cursor = 0;
            rejected = false;
            backoff = 1000;
        }
        // Explicit private IPv4 HTTP endpoints use WS; HTTPS always keeps verified TLS.
        boolean wanted = receiveEvents && token != null && server != null
                && (server.startsWith("https://") || BotgramServerAddress.isPrivateHttp(server)) && !rejected;
        if (!wanted) { if (active) stop(); return; }
        if (!active) {
            active = true;
            connect();
        }
    }

    void stop() {
        active = false;
        generation++;
        if (retry != null) AndroidUtilities.cancelRunOnUIThread(retry);
        retry = null;
        WebSocket previous = socket;
        socket = null;
        if (previous != null) close(previous);
    }

    private static void close(WebSocket ws) {
        network.execute(() -> {
            ws.disconnect();
            try { if (ws.getSocket() != null) ws.getSocket().close(); }
            catch (java.io.IOException ignored) { }
        });
    }

    private boolean current(long attempt) { return active && generation == attempt; }

    private void connect() {
        final long attempt = ++generation;
        final String origin = server;
        final String bearer = token;
        final boolean largeClientHello = credentials.largeClientHello;
        final long after = cursor;
        network.execute(() -> {
            try {
                // Validate the origin exactly as RPC does. No user info, query, or redirect target is accepted.
                String base = new BotgramApi(origin, bearer).baseUrl;
                WebSocketFactory factory = new WebSocketFactory().setConnectionTimeout(10_000).setSocketTimeout(20_000);
                if (base.startsWith("https://")) {
                    SSLContext tls = SSLContext.getInstance("TLS");
                    tls.init(null, null, null); // System trust managers only.
                    java.net.URI endpoint = java.net.URI.create(base);
                    factory.setSSLSocketFactory(largeClientHello
                            ? new BotgramLargeClientHelloSocketFactory(endpoint.getHost(),
                                    endpoint.getPort() == -1 ? 443 : endpoint.getPort())
                            : tls.getSocketFactory()).setVerifyHostname(true);
                }
                WebSocket ws = factory.createSocket(BotgramServerAddress.eventEndpoint(base, after));
                // JSON event subprotocol is retired. Only Protobuf hints are offered.
                ws.addProtocol("botgram.event-hint.v1.protobuf");
                ws.addHeader("Authorization", "Bearer " + bearer);
                ws.setPingInterval(15_000);
                ws.addListener(new WebSocketAdapter() {
                    private void reject(WebSocket connected, Exception error) {
                        close(connected);
                        AndroidUtilities.runOnUIThread(() -> failed(attempt, error));
                    }
                    @Override public void onStateChanged(WebSocket connected, WebSocketState state) {
                        // nv 2.14 calls OPEN before starting threads; onConnected can race its reader.
                        if (state == WebSocketState.OPEN) BotgramReceiveLimits.configure(connected);
                    }
                    @Override public void onConnected(WebSocket connected, Map<String, List<String>> headers) {
                        String protocol = connected.getAgreedProtocol();
                        if (!"botgram.event-hint.v1.protobuf".equals(protocol)) {
                            reject(connected, new IllegalArgumentException("Unsupported event protocol"));
                            return;
                        }
                        BotgramReceiveLimits.configure(connected);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!current(attempt)) { close(connected); return; }
                            backoff = 1000;
                            org.telegram.messenger.FileLog.d("BOTGRAM_EVENT_SOCKET connected transport=" + connected.getURI().getScheme());
                            listener.wake(credentials);
                            listener.wallpaper(credentials, -1);
                        });
                    }
                    @Override public void onTextMessage(WebSocket connected, String message) {
                        // Text frames belong to the retired JSON event channel.
                        reject(connected, new IllegalArgumentException("BOTGRAM_JSON_API_RETIRED"));
                    }
                    @Override public void onBinaryMessage(WebSocket connected, byte[] message) {
                        try {
                            if (!"botgram.event-hint.v1.protobuf".equals(connected.getAgreedProtocol())) {
                                throw new IllegalArgumentException("Unexpected binary event frame");
                            }
                            if (message.length > 1024) throw new IllegalArgumentException("Event hint too large");
                            validateHintWire(message);
                            CodedInputStream input = CodedInputStream.newInstance(message);
                            input.setRecursionLimit(32);
                            BotgramRpc.EventHint hint = BotgramRpc.EventHint.parseFrom(input);
                            input.checkLastTagWas(0);
                            if (!input.isAtEnd()) throw new IllegalArgumentException("Trailing event hint data");
                            long nextCursor = hint.getCursor();
                            if (hint.getProtocolMajor() != 1 || nextCursor < 0
                                    || hint.hasWallpaperRevision() && hint.getWallpaperRevision() < 0) {
                                throw new IllegalArgumentException("Invalid event hint");
                            }
                            AndroidUtilities.runOnUIThread(() -> {
                                if (!current(attempt)) return;
                                cursor = Math.max(cursor, nextCursor);
                                listener.wake(credentials);
                                if (hint.hasWallpaperRevision()) listener.wallpaper(credentials, hint.getWallpaperRevision());
                            });
                        } catch (Exception error) { reject(connected, error); }
                    }
                    @Override public void onDisconnected(WebSocket disconnected, WebSocketFrame serverClose,
                                                         WebSocketFrame clientClose, boolean byServer) {
                        AndroidUtilities.runOnUIThread(() -> failed(attempt, null));
                    }
                });
                AndroidUtilities.runOnUIThread(() -> {
                    if (!current(attempt)) { close(ws); return; }
                    socket = ws;
                    network.execute(() -> {
                        try {
                            if (generation != attempt) { close(ws); return; }
                            // nv 2.14 rejects non-101 handshakes, including redirects; never follow Location.
                            ws.connect();
                        } catch (Exception error) {
                            close(ws);
                            AndroidUtilities.runOnUIThread(() -> failed(attempt, error));
                        }
                    });
                });
            } catch (Exception error) {
                AndroidUtilities.runOnUIThread(() -> failed(attempt, error));
            }
        });
    }

    private void failed(long attempt, Exception error) {
        if (!current(attempt)) return;
        if (error instanceof OpeningHandshakeException
                && ((OpeningHandshakeException) error).getStatusLine().getStatusCode() == 401) {
            rejected = true;
            stop();
            listener.unauthorized(credentials);
            return;
        }
        generation++;
        WebSocket previous = socket;
        socket = null;
        if (previous != null) close(previous);
        retry = () -> {
            retry = null;
            if (active) connect();
        };
        AndroidUtilities.runOnUIThread(retry, backoff);
        backoff = Math.min(30_000, backoff * 2);
    }

    // EventHint has no embedded messages. Length-delimited unknown fields are opaque;
    // only legacy groups introduce structural nesting in this schema.
    static void validateHintWire(byte[] message) throws java.io.IOException {
        CodedInputStream wire = CodedInputStream.newInstance(message);
        int[] groups = new int[32];
        int depth = 0;
        while (!wire.isAtEnd()) {
            int tag = wire.readTag();
            int field = tag >>> 3;
            switch (tag & 7) {
                case 0: wire.readRawVarint64(); break;
                case 1: wire.skipRawBytes(8); break;
                case 2: wire.skipRawBytes(wire.readRawVarint32()); break;
                case 3:
                    if (depth == groups.length) throw new java.io.IOException("Event hint nesting too deep");
                    groups[depth++] = field;
                    break;
                case 4:
                    if (depth == 0 || groups[--depth] != field) {
                        throw new java.io.IOException("Unmatched event hint group");
                    }
                    break;
                case 5: wire.skipRawBytes(4); break;
                default: throw new java.io.IOException("Invalid event hint wire type");
            }
        }
        if (depth != 0) throw new java.io.IOException("Unclosed event hint group");
    }

    long generation() { return generation; }
}
