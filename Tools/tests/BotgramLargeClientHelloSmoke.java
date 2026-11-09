import org.telegram.botgram.BotgramLargeClientHelloSocketFactory;
import org.telegram.botgram.BotgramTlsDiagnostics;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertPathValidatorException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BotgramLargeClientHelloSmoke {
    private static int checks;

    private static void diagnostic(Throwable cause, String code) {
        javax.net.ssl.SSLHandshakeException error = new javax.net.ssl.SSLHandshakeException("private-response-marker");
        error.initCause(cause);
        String detail = BotgramTlsDiagnostics.describe(error);
        if (!detail.contains(code) || detail.contains("private-response-marker"))
            throw new AssertionError("TLS diagnostics must retain a safe reason without exception messages");
        checks++;
    }

    private static final class ClientHello {
        final int recordLength;
        final String serverName;

        ClientHello(int recordLength, String serverName) {
            this.recordLength = recordLength;
            this.serverName = serverName;
        }
    }

    private static String serverName(byte[] body) {
        ByteBuffer hello = ByteBuffer.wrap(body);
        hello.position(38); // Handshake header, legacy_version and random.
        int sessionIdLength = hello.get() & 255;
        hello.position(hello.position() + sessionIdLength);
        int cipherLength = hello.getShort() & 65535;
        hello.position(hello.position() + cipherLength);
        int compressionLength = hello.get() & 255;
        hello.position(hello.position() + compressionLength);
        int extensionLength = hello.getShort() & 65535;
        int end = hello.position() + extensionLength;
        while (hello.position() < end) {
            int type = hello.getShort() & 65535;
            int length = hello.getShort() & 65535;
            if (type == 0) {
                hello.getShort(); // ServerNameList length.
                if (hello.get() != 0) throw new AssertionError("HostName SNI required");
                byte[] name = new byte[hello.getShort() & 65535];
                hello.get(name);
                return new String(name, StandardCharsets.US_ASCII);
            }
            hello.position(hello.position() + length);
        }
        return null;
    }

    private static void capture(String host, String protocol, String expectedServerName) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<ClientHello> captured = new CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(5000);
                    InputStream input = peer.getInputStream();
                    byte[] header = input.readNBytes(5);
                    int length = ((header[3] & 255) << 8) + (header[4] & 255);
                    byte[] body = input.readNBytes(length);
                    if (header[0] != 22 || body.length != length || body[0] != 1)
                        throw new AssertionError("A complete ClientHello record is required");
                    captured.complete(new ClientHello(length + 5, serverName(body)));
                } catch (Throwable error) {
                    captured.completeExceptionally(error);
                }
            });
            reader.start();
            BotgramLargeClientHelloSocketFactory factory = new BotgramLargeClientHelloSocketFactory(host, 443);
            try (Socket tcp = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                 SSLSocket socket = (SSLSocket) factory.createSocket(tcp, host, 443, true)) {
                socket.setEnabledProtocols(new String[]{protocol});
                socket.setSoTimeout(5000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setServerNames(Collections.emptyList());
                parameters.setEndpointIdentificationAlgorithm(null);
                socket.setSSLParameters(parameters);
                try {
                    socket.startHandshake();
                    throw new AssertionError("Capture sink cannot complete a TLS handshake");
                } catch (SSLException expected) {
                    // The sink deliberately closes after recording the first outbound TLS record.
                }
                if (!"HTTPS".equals(socket.getSSLParameters().getEndpointIdentificationAlgorithm()))
                    throw new AssertionError("HTTPS hostname verification must be enabled");
                for (String offer : socket.getSSLParameters().getApplicationProtocols()) {
                    if (offer.length() > 127)
                        throw new AssertionError("ALPN offers must round-trip Android's signed length decoder");
                }
            }
            ClientHello hello = captured.get(8, TimeUnit.SECONDS);
            if (hello.recordLength != 1500)
                throw new AssertionError(host + " " + protocol + " measured " + hello.recordLength);
            if (!Objects.equals(hello.serverName, expectedServerName))
                throw new AssertionError("Unexpected SNI for " + host + ": " + hello.serverName);
            reader.join();
            checks++;
            System.out.println("PASS " + protocol + " " + host + " ClientHello record=" + hello.recordLength
                    + " SNI=" + hello.serverName);
        }
    }

    private static void network(String endpoint) throws Exception {
        URL url = new URL(endpoint);
        if (!url.getProtocol().equals("https")) throw new IllegalArgumentException("HTTPS endpoint required");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setSSLSocketFactory(new BotgramLargeClientHelloSocketFactory(url.getHost(),
                url.getPort() < 0 ? 443 : url.getPort()));
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-protobuf");
        connection.setRequestProperty("Accept", "application/x-protobuf");
        // Read-only AuthCapabilities RPC, with a matching protocol-major and request ID.
        byte[] request = new byte[]{8, 1, 18, 5, 'p', 'r', 'o', 'b', 'e', (byte) 194, 12, 0};
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(request.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(request); }
            if (connection.getResponseCode() != 200) throw new AssertionError("HTTP " + connection.getResponseCode());
            if (!connection.getContentType().startsWith("application/x-protobuf"))
                throw new AssertionError("Protobuf response required");
            try (InputStream input = connection.getInputStream()) {
                if (input.readAllBytes().length == 0) throw new AssertionError("Missing response");
            }
            checks++;
            System.out.println("PASS HttpsURLConnection with system certificate and hostname validation");
        } finally {
            connection.disconnect();
        }
    }

    public static void main(String[] args) throws Exception {
        diagnostic(new CertificateExpiredException("private-response-marker"), "CERTIFICATE_EXPIRED");
        diagnostic(new CertificateNotYetValidException("private-response-marker"), "CERTIFICATE_NOT_YET_VALID");
        diagnostic(new CertPathValidatorException("Trust anchor private-response-marker"), "CERTIFICATE_TRUST_ANCHOR_MISSING");
        diagnostic(new CertificateException("No subject alternative DNS name private-response-marker"), "CERTIFICATE_HOSTNAME_MISMATCH");
        diagnostic(new SSLException("protocol_version private-response-marker"), "TLS_PROTOCOL_VERSION_REJECTED");
        for (String protocol : new String[]{"TLSv1.2", "TLSv1.3"}) {
            for (String[] test : new String[][]{
                    {"example.test", "example.test"}, {"127.0.0.1", null}, {"::1", null},
                    {"long-hostname-for-clienthello-size.example.test", "long-hostname-for-clienthello-size.example.test"}})
                capture(test[0], protocol, test[1]);
        }
        if (args.length > 0) network(args[0]);
        System.out.println("PASS " + checks + " large ClientHello checks");
    }
}
