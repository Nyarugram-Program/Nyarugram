package org.telegram.botgram;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.botgram.protocol.v1.BotgramRpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Only used from background threads. Never forwards credentials to another origin. */
public final class BotgramApi {
    public static final String AUTH_PATH = "/api/v1/auth";
    public static final String ACCOUNT_PATH = "/api/v1/account";
    public static final String RPC_PATH = "/api/v1/rpc";
    public static final String JSON_API_RETIRED = "BOTGRAM_JSON_API_RETIRED";

    public static final class ApiError extends IOException {
        public final int status;
        ApiError(int status, String message) { super(message); this.status = status; }
        ApiError(int status, String message, Throwable cause) { super(message, cause); this.status = status; }
    }

    public final String baseUrl;
    private final String token;
    private final boolean largeClientHello;
    public boolean usesLargeClientHello() { return largeClientHello; }
    private String selectedProtocol;

    /** Protobuf-only capability negotiation before any RPC; the protocol is pinned to this bearer. */
    public synchronized String negotiate() throws Exception {
        if (selectedProtocol != null) return selectedProtocol;
        BotgramRpc.RpcRequest request = BotgramRpc.RpcRequest.newBuilder()
                .setProtocolMajor(1)
                .setRequestId(UUID.randomUUID().toString())
                .setAuthCapabilities(BotgramRpc.AuthCapabilitiesRequest.getDefaultInstance())
                .build();
        BotgramRpc.RpcResponse response = authRpc(request, false);
        if (!response.hasAuthCapabilities()) throw new ApiError(502, "BOTGRAM_NEGOTIATION_INVALID");
        BotgramRpc.AuthCapabilities caps = response.getAuthCapabilities();
        String selected = caps.getSelectedProtocol();
        boolean advertised = false, idempotent = false;
        for (String protocol : caps.getProtocolsList()) if (selected.equals(protocol)) advertised = true;
        for (String capability : caps.getCapabilitiesList()) {
            if ("send-random-id-idempotency-v1".equals(capability)) idempotent = true;
        }
        if (caps.getNegotiationVersion() != 1 || !advertised || !idempotent || !"protobuf-v1".equals(selected)) {
            throw new ApiError(502, "BOTGRAM_NEGOTIATION_INVALID");
        }
        selectedProtocol = selected;
        return selected;
    }

    public BotgramApi(String baseUrl, String token) {
        this(baseUrl, token, false);
    }

    public BotgramApi(String baseUrl, String token, boolean largeClientHello) {
        URI uri = URI.create(baseUrl.trim());
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("服务器地址必须是 http(s)://主机[:端口]，不含路径或凭据");
        }
        if (!"https".equals(uri.getScheme()) && !BotgramServerAddress.isPrivateHttp(baseUrl)
                && !org.telegram.messenger.BuildVars.DEBUG_VERSION) {
            throw new IllegalArgumentException("BOTGRAM_HTTPS_REQUIRED");
        }
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.token = token;
        this.largeClientHello = largeClientHello;
    }

    /** Retained only for diagnostics; business traffic never uses JSON HTTP. */
    public JSONObject object(String method, String path, JSONObject body) throws Exception {
        String value = request(method, path, body);
        return value.isEmpty() ? new JSONObject() : new JSONObject(value);
    }

    public JSONArray array(String path) throws Exception { return new JSONArray(request("GET", path, null)); }

    private void configureTls(HttpURLConnection connection) throws IOException {
        if (largeClientHello && connection instanceof javax.net.ssl.HttpsURLConnection) {
            if (android.os.Build.VERSION.SDK_INT < 29)
                throw new ApiError(501, "BOTGRAM_LARGE_CLIENT_HELLO_UNSUPPORTED");
            URL endpoint = connection.getURL();
            ((javax.net.ssl.HttpsURLConnection) connection).setSSLSocketFactory(
                    new BotgramLargeClientHelloSocketFactory(endpoint.getHost(),
                            endpoint.getPort() == -1 ? 443 : endpoint.getPort()));
        }
    }

    public String request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "text/plain");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            configureTls(connection);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = "";
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (buffer.size() + count > 8 * 1024 * 1024) throw new IOException("响应过大");
                        buffer.write(chunk, 0, count);
                    }
                    response = buffer.toString("UTF-8");
                }
            }
            if (status < 200 || status >= 300) {
                if (status == 410 && response.startsWith(JSON_API_RETIRED)) {
                    throw new ApiError(410, JSON_API_RETIRED);
                }
                String message = "HTTP " + status;
                try { message = new JSONObject(response).optString("error", message); } catch (Exception ignored) { }
                throw new ApiError(status, message);
            }
            return response;
        } catch (javax.net.ssl.SSLPeerUnverifiedException error) {
            throw new ApiError(495, "BOTGRAM_TLS_PEER_UNVERIFIED", error);
        } catch (javax.net.ssl.SSLHandshakeException error) {
            throw new ApiError(495, "BOTGRAM_TLS_HANDSHAKE_FAILED", error);
        } finally { connection.disconnect(); }
    }

    /** Main business RPC plane; requires a valid Bearer session. */
    public BotgramRpc.RpcResponse rpc(BotgramRpc.RpcRequest request) throws Exception {
        return send(RPC_PATH, request);
    }

    /**
     * Protobuf authentication plane. {@code accountPlane=false} uses the
     * unauthenticated /api/v1/auth endpoint; {@code true} uses /api/v1/account.
     */
    public BotgramRpc.RpcResponse authRpc(BotgramRpc.RpcRequest request, boolean accountPlane) throws Exception {
        return send(accountPlane ? ACCOUNT_PATH : AUTH_PATH, request);
    }

    private BotgramRpc.RpcResponse send(String path, BotgramRpc.RpcRequest request) throws Exception {
        byte[] bytes = request.toByteArray();
        if (bytes.length > 128 * 1024) throw new ApiError(413, "BOTGRAM_REQUEST_TOO_LARGE");
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Accept", "application/x-protobuf");
        connection.setRequestProperty("Content-Type", "application/x-protobuf");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            configureTls(connection);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] responseBytes = new byte[0];
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (buffer.size() + count > 8 * 1024 * 1024) throw new ApiError(502, "BOTGRAM_RESPONSE_TOO_LARGE");
                        buffer.write(chunk, 0, count);
                    }
                    responseBytes = buffer.toByteArray();
                }
            }
            // A retired server answers 410 with a plain-text code, never JSON.
            if (status == 410 && new String(responseBytes, StandardCharsets.UTF_8).startsWith(JSON_API_RETIRED)) {
                throw new ApiError(410, JSON_API_RETIRED);
            }
            String type = connection.getContentType();
            if (type == null || !"application/x-protobuf".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
                throw new ApiError(status >= 400 ? status : 502, "BOTGRAM_PROTOBUF_MEDIA_TYPE_INVALID");
            }
            if (stream == null) throw new ApiError(status >= 400 ? status : 502, "BOTGRAM_PROTOBUF_RESPONSE_MISSING");
            com.google.protobuf.CodedInputStream input = com.google.protobuf.CodedInputStream.newInstance(responseBytes);
            input.setRecursionLimit(32);
            BotgramRpc.RpcResponse response = BotgramRpc.RpcResponse.parseFrom(input);
            if (response.getProtocolMajor() != 1 || (!response.getRequestId().equals(request.getRequestId())
                    && !(status >= 400 && response.getRequestId().isEmpty()))) {
                throw new ApiError(502, "BOTGRAM_PROTOBUF_ENVELOPE_INVALID");
            }
            if (response.hasError()) {
                if (status < 400 || response.getError().getHttpStatus() != status) {
                    throw new ApiError(502, "BOTGRAM_PROTOBUF_STATUS_INVALID");
                }
                throw new ApiError(status, response.getError().getCode());
            }
            if (status < 200 || status >= 300) throw new ApiError(status, "BOTGRAM_PROTOBUF_HTTP_ERROR");
            if (response.getOutcomeCase().getNumber() != request.getOperationCase().getNumber()) {
                throw new ApiError(502, "BOTGRAM_PROTOBUF_OUTCOME_INVALID");
            }
            return response;
        } catch (javax.net.ssl.SSLPeerUnverifiedException error) {
            throw new ApiError(495, "BOTGRAM_TLS_PEER_UNVERIFIED", error);
        } catch (javax.net.ssl.SSLHandshakeException error) {
            throw new ApiError(495, "BOTGRAM_TLS_HANDSHAKE_FAILED", error);
        } finally { connection.disconnect(); }
    }

    /** One media byte range returned by the binary download plane. */
    public static final class MediaRange {
        public final byte[] bytes;
        public final long totalSize;
        MediaRange(byte[] bytes, long totalSize) { this.bytes = bytes; this.totalSize = totalSize; }
    }

    /**
     * One upload part over the binary media plane. The body is raw octets, never a
     * Protobuf envelope or JSON; the same Bearer session authorizes it.
     */
    public void uploadPart(long clientFileId, int part, int totalParts, byte[] bytes) throws Exception {
        String path = "/api/v1/media/" + clientFileId + "/" + part
                + (totalParts > 0 ? "?total=" + totalParts : "");
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Accept", "text/plain");
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            configureTls(connection);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw mediaError(connection, status);
            drain(connection);
        } catch (javax.net.ssl.SSLPeerUnverifiedException error) {
            throw new ApiError(495, "BOTGRAM_TLS_PEER_UNVERIFIED", error);
        } catch (javax.net.ssl.SSLHandshakeException error) {
            throw new ApiError(495, "BOTGRAM_TLS_HANDSHAKE_FAILED", error);
        } finally { connection.disconnect(); }
    }

    /** A bounded byte range of one media file; authorization is conversation membership. */
    public MediaRange downloadRange(long mediaId, long offset, int limit) throws Exception {
        String path = "/api/v1/media/" + mediaId + "?offset=" + offset + "&limit=" + limit;
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/octet-stream");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            configureTls(connection);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw mediaError(connection, status);
            long totalSize = -1;
            try { totalSize = Long.parseLong(connection.getHeaderField("X-Botgram-Media-Size")); }
            catch (Exception ignored) { }
            byte[] bytes = readFully(connection.getInputStream(), limit + 1);
            if (bytes.length > limit) throw new ApiError(502, "BOTGRAM_MEDIA_RESPONSE_TOO_LARGE");
            return new MediaRange(bytes, totalSize);
        } catch (javax.net.ssl.SSLPeerUnverifiedException error) {
            throw new ApiError(495, "BOTGRAM_TLS_PEER_UNVERIFIED", error);
        } catch (javax.net.ssl.SSLHandshakeException error) {
            throw new ApiError(495, "BOTGRAM_TLS_HANDSHAKE_FAILED", error);
        } finally { connection.disconnect(); }
    }

    private static byte[] readFully(InputStream stream, int limit) throws IOException {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (buffer.size() + count > limit) throw new IOException("BOTGRAM_MEDIA_RESPONSE_TOO_LARGE");
                buffer.write(chunk, 0, count);
            }
            return buffer.toByteArray();
        }
    }

    private static void drain(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getInputStream();
        if (stream != null) try (InputStream input = stream) { while (input.read() != -1) { } }
    }

    /** The media plane answers plain text: the first line is a symbolic code. */
    private static ApiError mediaError(HttpURLConnection connection, int status) {
        String code = "BOTGRAM_MEDIA_HTTP_" + status;
        try {
            byte[] body = readFully(connection.getErrorStream(), 4096);
            String text = new String(body, StandardCharsets.UTF_8);
            int newline = text.indexOf('\n');
            String first = (newline >= 0 ? text.substring(0, newline) : text).trim();
            if (!first.isEmpty() && first.length() <= 200 && first.matches("[A-Za-z0-9_:.-]+")) code = first;
        } catch (Exception ignored) { }
        return new ApiError(status, code);
    }

    public String token() { return token; }

    public static JSONObject json(Object... fields) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }

    public static BotgramRpc.RpcRequest.Builder request() {
        return BotgramRpc.RpcRequest.newBuilder().setProtocolMajor(1).setRequestId(UUID.randomUUID().toString());
    }
}
