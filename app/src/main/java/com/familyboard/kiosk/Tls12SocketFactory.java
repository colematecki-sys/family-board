package com.familyboard.kiosk;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Android 4.4 supports TLS 1.2 but leaves it switched off; this turns it on for every socket. */
class Tls12SocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory base;

    Tls12SocketFactory() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, null, null);
        base = ctx.getSocketFactory();
    }

    private static Socket patch(Socket s) {
        if (s instanceof SSLSocket) {
            ((SSLSocket) s).setEnabledProtocols(new String[] {"TLSv1.1", "TLSv1.2"});
        }
        return s;
    }

    @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException { return patch(base.createSocket()); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return patch(base.createSocket(s, host, port, autoClose));
    }
    @Override public Socket createSocket(String host, int port) throws IOException {
        return patch(base.createSocket(host, port));
    }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return patch(base.createSocket(host, port, localHost, localPort));
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return patch(base.createSocket(host, port));
    }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return patch(base.createSocket(address, port, localAddress, localPort));
    }
}
