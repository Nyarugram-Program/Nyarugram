package org.telegram.botgram;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** API 29+: use valid ALPN offers to enlarge ClientHello without editing TLS transcript bytes. */
public final class BotgramLargeClientHelloSocketFactory extends SSLSocketFactory {
    static final int TARGET_RECORD_BYTES = 1500;
    private final SSLSocketFactory delegate;
    private final String host;
    private final int port;

    public BotgramLargeClientHelloSocketFactory(String host, int port) throws SSLException {
        delegate = newContext().getSocketFactory();
        this.host = host;
        this.port = port;
    }

    private static SSLContext newContext() throws SSLException {
        try {
            // Each socket owns its context so measurement cannot consume a cached session ticket.
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, null, null);
            return context;
        } catch (GeneralSecurityException error) {
            throw new SSLException(error);
        }
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException {
        SSLContext context = newContext();
        return new PaddedSocket(context, (SSLSocket) context.getSocketFactory().createSocket());
    }
    @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws IOException {
        SSLContext context = newContext();
        return new PaddedSocket(context, (SSLSocket) context.getSocketFactory().createSocket(socket, host, port, close));
    }
    @Override public Socket createSocket(String host, int port) throws IOException {
        SSLContext context = newContext();
        return new PaddedSocket(context, (SSLSocket) context.getSocketFactory().createSocket(host, port));
    }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        SSLContext context = newContext();
        return new PaddedSocket(context, (SSLSocket) context.getSocketFactory().createSocket(host, port, local, localPort));
    }
    @Override public Socket createSocket(InetAddress address, int port) throws IOException {
        SSLContext context = newContext();
        return new PaddedSocket(context, (SSLSocket) context.getSocketFactory().createSocket(address, port));
    }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
        SSLContext context = newContext();
        return new PaddedSocket(context, (SSLSocket) context.getSocketFactory().createSocket(address, port, local, localPort));
    }

    static SSLParameters paddedParameters(SSLContext context, SSLParameters parameters, String host, int port)
            throws SSLException {
        // The wrapper is not a platform Conscrypt socket, so Android's HTTP stack may skip
        // its usual setHostname hook. Set SNI explicitly on both the probe and real socket.
        if (host.indexOf(':') >= 0 || host.matches("[0-9]+(\\.[0-9]+){3}")) {
            parameters.setServerNames(Collections.emptyList());
        } else {
            parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
        }
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        int paddingBytes = 0;
        for (int pass = 0; pass < 6; pass++) {
            ArrayList<String> protocols = new ArrayList<>();
            protocols.add("http/1.1");
            int remaining = paddingBytes;
            while (remaining > 0) {
                // Android Conscrypt decodes ALPN length bytes as signed values when
                // reading SSLParameters during certificate validation. Keep each
                // protocol at most 127 bytes so this platform path can round-trip it.
                int encodedLength = Math.min(128, remaining);
                if (remaining - encodedLength == 1) encodedLength--;
                // An ALPN entry needs one length byte and at least one protocol byte.
                encodedLength = Math.max(2, encodedLength);
                char[] value = new char[encodedLength - 1];
                Arrays.fill(value, 'x');
                protocols.add(new String(value));
                remaining -= encodedLength;
            }
            parameters.setApplicationProtocols(protocols.toArray(new String[0]));
            int length = clientHelloLength(context, parameters, host, port);
            if (length >= TARGET_RECORD_BYTES) return parameters;
            // Re-measure because platform TLS padding can disappear once ClientHello grows.
            paddingBytes += TARGET_RECORD_BYTES - length;
        }
        throw new SSLException("BOTGRAM_CLIENT_HELLO_PADDING_FAILED");
    }

    private static int clientHelloLength(SSLContext context, SSLParameters parameters, String host, int port)
            throws SSLException {
        SSLEngine engine = context.createSSLEngine(host, port);
        engine.setUseClientMode(true);
        engine.setSSLParameters(parameters);
        ByteBuffer output = ByteBuffer.allocate(65536);
        try {
            engine.beginHandshake();
            for (int pass = 0; pass < 8; pass++) {
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) task.run();
                SSLEngineResult result = engine.wrap(ByteBuffer.allocate(0), output);
                if (output.position() >= 9) {
                    byte[] bytes = output.array();
                    if (bytes[0] != 22 || bytes[5] != 1) break;
                    int length = 5 + ((bytes[3] & 255) << 8) + (bytes[4] & 255);
                    if (output.position() >= length) return length;
                }
                if (result.getStatus() != SSLEngineResult.Status.OK) break;
            }
        } finally {
            engine.closeOutbound();
        }
        throw new SSLException("BOTGRAM_CLIENT_HELLO_MEASUREMENT_FAILED");
    }

    private final class PaddedSocket extends SSLSocket {
        private final SSLContext context;
        private final SSLSocket socket;
        private boolean prepared;

        private PaddedSocket(SSLContext context, SSLSocket socket) {
            this.context = context;
            this.socket = socket;
        }

        private synchronized void prepare() throws SSLException {
            if (!prepared) {
                socket.setSSLParameters(paddedParameters(context, socket.getSSLParameters(), host, port));
                prepared = true;
            }
        }

        @Override public void startHandshake() throws IOException { prepare(); socket.startHandshake(); }
        @Override public InputStream getInputStream() throws IOException { prepare(); return socket.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException { prepare(); return socket.getOutputStream(); }
        @Override public SSLSession getSession() {
            try { prepare(); } catch (SSLException error) { throw new IllegalStateException(error); }
            return socket.getSession();
        }
        @Override public SSLSession getHandshakeSession() { return socket.getHandshakeSession(); }
        @Override public SSLParameters getSSLParameters() { return socket.getSSLParameters(); }
        @Override public void setSSLParameters(SSLParameters value) { socket.setSSLParameters(value); prepared = false; }
        @Override public String getApplicationProtocol() { return socket.getApplicationProtocol(); }
        @Override public String[] getSupportedCipherSuites() { return socket.getSupportedCipherSuites(); }
        @Override public String[] getEnabledCipherSuites() { return socket.getEnabledCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] value) { socket.setEnabledCipherSuites(value); prepared = false; }
        @Override public String[] getSupportedProtocols() { return socket.getSupportedProtocols(); }
        @Override public String[] getEnabledProtocols() { return socket.getEnabledProtocols(); }
        @Override public void setEnabledProtocols(String[] value) { socket.setEnabledProtocols(value); prepared = false; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener value) { socket.addHandshakeCompletedListener(value); }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener value) { socket.removeHandshakeCompletedListener(value); }
        @Override public void setUseClientMode(boolean value) { socket.setUseClientMode(value); prepared = false; }
        @Override public boolean getUseClientMode() { return socket.getUseClientMode(); }
        @Override public void setNeedClientAuth(boolean value) { socket.setNeedClientAuth(value); }
        @Override public boolean getNeedClientAuth() { return socket.getNeedClientAuth(); }
        @Override public void setWantClientAuth(boolean value) { socket.setWantClientAuth(value); }
        @Override public boolean getWantClientAuth() { return socket.getWantClientAuth(); }
        @Override public void setEnableSessionCreation(boolean value) { socket.setEnableSessionCreation(value); }
        @Override public boolean getEnableSessionCreation() { return socket.getEnableSessionCreation(); }
        @Override public void connect(SocketAddress address) throws IOException { socket.connect(address); }
        @Override public void connect(SocketAddress address, int timeout) throws IOException { socket.connect(address, timeout); }
        @Override public void bind(SocketAddress address) throws IOException { socket.bind(address); }
        @Override public InetAddress getInetAddress() { return socket.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return socket.getLocalAddress(); }
        @Override public int getPort() { return socket.getPort(); }
        @Override public int getLocalPort() { return socket.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return socket.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return socket.getLocalSocketAddress(); }
        @Override public void setSoTimeout(int value) throws SocketException { socket.setSoTimeout(value); }
        @Override public int getSoTimeout() throws SocketException { return socket.getSoTimeout(); }
        @Override public void setTcpNoDelay(boolean value) throws SocketException { socket.setTcpNoDelay(value); }
        @Override public boolean getTcpNoDelay() throws SocketException { return socket.getTcpNoDelay(); }
        @Override public void setKeepAlive(boolean value) throws SocketException { socket.setKeepAlive(value); }
        @Override public boolean getKeepAlive() throws SocketException { return socket.getKeepAlive(); }
        @Override public void setSoLinger(boolean enabled, int value) throws SocketException { socket.setSoLinger(enabled, value); }
        @Override public int getSoLinger() throws SocketException { return socket.getSoLinger(); }
        @Override public void setSendBufferSize(int value) throws SocketException { socket.setSendBufferSize(value); }
        @Override public int getSendBufferSize() throws SocketException { return socket.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int value) throws SocketException { socket.setReceiveBufferSize(value); }
        @Override public int getReceiveBufferSize() throws SocketException { return socket.getReceiveBufferSize(); }
        @Override public void setReuseAddress(boolean value) throws SocketException { socket.setReuseAddress(value); }
        @Override public boolean getReuseAddress() throws SocketException { return socket.getReuseAddress(); }
        @Override public void setTrafficClass(int value) throws SocketException { socket.setTrafficClass(value); }
        @Override public int getTrafficClass() throws SocketException { return socket.getTrafficClass(); }
        @Override public void shutdownInput() throws IOException { socket.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { socket.shutdownOutput(); }
        @Override public boolean isConnected() { return socket.isConnected(); }
        @Override public boolean isBound() { return socket.isBound(); }
        @Override public boolean isClosed() { return socket.isClosed(); }
        @Override public boolean isInputShutdown() { return socket.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return socket.isOutputShutdown(); }
        @Override public void close() throws IOException { socket.close(); }
    }
}
