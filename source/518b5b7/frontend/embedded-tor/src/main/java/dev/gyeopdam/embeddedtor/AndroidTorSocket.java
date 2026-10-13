package dev.gyeopdam.embeddedtor;

import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/** Public API26 only. No TCP, descriptor adoption, reflection, new JNI, or stream flush. */
final class AndroidTorSocket implements TorSocketIo {
    // Public API26 Os accepts flags but does not expose these OsConstants fields.
    // ABI-independent Android8 bionic libc/include/sys/socket.h defines these values.
    private static final int MSG_DONTWAIT = 0x40, MSG_NOSIGNAL = 0x4000;
    private final TorSocketEndpoint endpoint;
    private final LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_STREAM);
    private final FileDescriptor descriptor;
    private Credentials peer;

    AndroidTorSocket(TorSocketEndpoint endpoint) throws IOException {
        this.endpoint = endpoint;
        TorSocketPaths.verify(endpoint);
        // LocalSocket creates its FD lazily. Precreate before publishing to cancellation.
        socket.getInputStream();
        descriptor = socket.getFileDescriptor();
        if (descriptor == null) { socket.close(); throw new IOException("TOR_SOCKET_UNAVAILABLE"); }
    }
    @Override public void connect(int timeoutMillis) throws IOException {
        // connect(address, timeout) is unsupported on API26. setSoTimeout sets both
        // SO_RCVTIMEO and SO_SNDTIMEO; Linux AF_UNIX connect uses the send timeout.
        socket.setSoTimeout(timeoutMillis);
        socket.connect(new LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM));
        peer = socket.getPeerCredentials();
        if (peer == null) throw new IOException("TOR_PEER_UNAVAILABLE");
    }
    @Override public int peerUid() throws IOException { if (peer == null) throw new IOException("TOR_PEER_UNAVAILABLE"); return peer.getUid(); }
    @Override public int peerPid() throws IOException { if (peer == null) throw new IOException("TOR_PEER_UNAVAILABLE"); return peer.getPid(); }
    @Override public int read(byte[] bytes, int length, int waitMillis) throws IOException {
        if (!poll(OsConstants.POLLIN, waitMillis)) return 0;
        try {
            int count = Os.recvfrom(descriptor, bytes, 0, length, MSG_DONTWAIT, (InetSocketAddress)null);
            return count == 0 ? -1 : count;
        } catch (ErrnoException unavailable) {
            if (unavailable.errno == OsConstants.EAGAIN || unavailable.errno == OsConstants.EINTR) return 0;
            throw new IOException("TOR_SOCKET_READ", unavailable);
        }
    }
    @Override public boolean awaitWritable(int waitMillis) throws IOException { return poll(OsConstants.POLLOUT, waitMillis); }
    @Override public int write(byte[] bytes, int length) throws IOException {
        try { return Os.sendto(descriptor, bytes, 0, length, MSG_DONTWAIT | MSG_NOSIGNAL, (InetAddress)null, 0); }
        catch (ErrnoException unavailable) {
            if (unavailable.errno == OsConstants.EAGAIN || unavailable.errno == OsConstants.EINTR) return 0;
            throw new IOException("TOR_SOCKET_WRITE", unavailable);
        }
    }
    private boolean poll(int events, int waitMillis) throws IOException {
        StructPollfd descriptorEvent = new StructPollfd();
        descriptorEvent.fd = descriptor; descriptorEvent.events = (short)events;
        try {
            if (Os.poll(new StructPollfd[]{descriptorEvent}, waitMillis) == 0) return false;
            if ((descriptorEvent.revents & OsConstants.POLLNVAL) != 0) throw new IOException("TOR_SOCKET_CLOSED");
            // HUP/ERR are delivered to recv/send so EOF or a fixed failure reaches Netty.
            return true;
        } catch (ErrnoException unavailable) {
            if (unavailable.errno == OsConstants.EINTR) return false;
            throw new IOException("TOR_SOCKET_POLL", unavailable);
        }
    }
    @Override public void shutdown() {
        try { Os.shutdown(descriptor, OsConstants.SHUT_RDWR); }
        catch (ErrnoException ignored) { /* Unconnected backlog wait is bounded by SO_SNDTIMEO. */ }
    }
    @Override public void close() throws IOException { socket.close(); }
}
