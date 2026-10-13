package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.function.BooleanSupplier;

/** Test-only loopback peers. Production code has no TCP connector or numeric-port constructor. */
final class HostTorSockets {
    static final int UID = 10001, PID = 22002;
    static final TorSocketIo.Factory FACTORY = endpoint -> new Loopback(endpoint);
    static TorSocketEndpoint endpoint(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("INVALID_HOST_FIXTURE_PORT");
        return new TorSocketEndpoint("/fixture/tor-socks/" + port, UID, PID);
    }
    static OnionApiTransport api(int port, BooleanSupplier ready) { return api(port, ready, OnionApiTransport.DEADLINE_MS); }
    static OnionApiTransport api(int port, BooleanSupplier ready, int deadline) {
        return new OnionApiTransport(endpoint(port), ready, deadline, FACTORY);
    }
    static FixedOnionProbe probe(int port, BooleanSupplier ready) { return probe(port, ready, FixedOnionProbe.TOTAL_TIMEOUT_MILLIS); }
    static FixedOnionProbe probe(int port, BooleanSupplier ready, int deadline) {
        return new FixedOnionProbe(endpoint(port), ready, deadline, FACTORY);
    }
    static final class Loopback implements TorSocketIo {
        final Socket socket = new Socket();
        final int port;
        Loopback(TorSocketEndpoint endpoint) { port = Integer.parseInt(endpoint.path.substring(endpoint.path.lastIndexOf('/') + 1)); }
        public void connect(int timeout) throws IOException {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), port), timeout);
        }
        public int peerUid() { return UID; }
        public int peerPid() { return PID; }
        public int read(byte[] bytes, int length, int wait) throws IOException {
            socket.setSoTimeout(wait);
            try { return socket.getInputStream().read(bytes, 0, length); }
            catch (SocketTimeoutException pending) { return 0; }
        }
        public boolean awaitWritable(int wait) { return true; }
        public int write(byte[] bytes, int length) throws IOException {
            socket.getOutputStream().write(bytes, 0, length); return length;
        }
        public void shutdown() {
            try { socket.shutdownInput(); } catch (IOException ignored) { }
            try { socket.shutdownOutput(); } catch (IOException ignored) { }
        }
        public void close() throws IOException { socket.close(); }
    }
}
