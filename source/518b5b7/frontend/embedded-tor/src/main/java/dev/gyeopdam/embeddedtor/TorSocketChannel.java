package dev.gyeopdam.embeddedtor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelId;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.FileRegion;
import io.netty.channel.EventLoop;
import io.netty.channel.oio.AbstractOioByteChannel;
import io.netty.channel.oio.OioEventLoopGroup;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Netty owns SOCKS/HTTP codecs; this bounded adapter owns only an authenticated Unix byte stream. */
@SuppressWarnings("deprecation")
final class TorSocketChannel extends AbstractOioByteChannel {
    private static final int CONNECT_MAX_MS = 1_000, POLL_MAX_MS = 100, CHUNK_BYTES = 8_192;
    private final Object descriptorLock = new Object();
    private final TorSocketEndpoint endpoint;
    private final BooleanSupplier ready;
    private final TorSocketIo.Factory factory;
    private final long deadline;
    private final ChannelConfig config = new DefaultChannelConfig(this);
    private volatile boolean open = true, active, aborted;
    private boolean closeSubmitted; // Guarded by descriptorLock, including the enqueue itself.
    private TorSocketIo socket; // Published/closed only under descriptorLock; I/O owner uses local references.

    TorSocketChannel(TorSocketEndpoint endpoint, BooleanSupplier ready, long deadline, TorSocketIo.Factory factory) {
        super(null); this.endpoint = endpoint; this.ready = ready; this.deadline = deadline; this.factory = factory;
    }
    @Override public ChannelConfig config() { return config; }
    @Override public boolean isOpen() { return open; }
    @Override public boolean isActive() { return open && active && !aborted; }
    @Override protected boolean isInputShutdown() { return !isActive(); }
    @Override protected ChannelFuture shutdownInput() { abort(); return newSucceededFuture(); }
    @Override protected SocketAddress localAddress0() { return null; }
    @Override protected SocketAddress remoteAddress0() { return active ? endpoint : null; }
    @Override protected void doBind(SocketAddress address) throws IOException { throw new IOException("TOR_BIND_FORBIDDEN"); }
    @Override protected void doDisconnect() throws IOException { doClose(); }

    /** Caller may be the independent request/deadline worker. Never releases an in-use FD. */
    void abort() {
        synchronized (descriptorLock) {
            if (closeSubmitted || !open) return;
            aborted = true;
            if (socket != null) socket.shutdown();
            closeSubmitted = true; // Publish before close() can re-enter through channelInactive.
            try { eventLoop(); }
            catch (IllegalStateException notRegistered) {
                // No owner or native I/O exists until registration. Netty close() itself
                // requires an assigned event loop, so release this empty channel directly.
                unsafe().closeForcibly(); return;
            }
            // Keep submission in the same short critical section. Another cleanup caller
            // cannot start owner termination while this caller is still enqueueing close.
            // No future wait or blocking socket I/O occurs while holding this monitor.
            close(); // Owner closes after its current syscall returns, never from the watchdog.
        }
    }
    static void shutdown(TorSocketChannel channel, OioEventLoopGroup group) {
        try {
            if (channel != null) {
                EventLoop owner = null;
                try { owner = channel.eventLoop(); }
                catch (IllegalStateException notRegistered) { /* No native I/O has begun. */ }
                try { channel.abort(); }
                finally {
                    // Netty's per-channel group moves children between active/idle sets
                    // non-atomically. Own the exact loop through termination first.
                    if (owner != null) owner.shutdownGracefully(0,1,TimeUnit.SECONDS).syncUninterruptibly();
                }
            }
        } finally {
            if (group != null) group.shutdownGracefully(0,1,TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
    @Override protected void doConnect(SocketAddress remote, SocketAddress local) throws Exception {
        if (local != null || !endpoint.equals(remote)) throw new IOException("TOR_ENDPOINT_MISMATCH");
        check();
        TorSocketIo opened = factory.create(endpoint);
        synchronized (descriptorLock) {
            if (aborted || !open) { opened.close(); throw new IOException("TOR_REVOKED"); }
            socket = opened;
        }
        try {
            check();
            opened.connect(waitMillis(CONNECT_MAX_MS));
            check();
            if (!endpoint.matches(opened.peerUid(), opened.peerPid())) throw new IOException("TOR_PEER_MISMATCH");
            check();
            // AbstractOioChannel emits channelActive only after doConnect returns. Consequently
            // no SOCKS greeting, onion hostname, HTTP body or credential precedes peer proof.
            active = true;
        } catch (Exception failure) { doClose(); throw failure; }
    }
    @Override protected int available() { return 0; } // Each bounded poll returns control to Netty.
    @Override protected int doReadBytes(ByteBuf target) throws Exception {
        check();
        byte[] bytes = new byte[Math.min(CHUNK_BYTES, target.writableBytes())];
        try {
            int count = socket.read(bytes, bytes.length, waitMillis(POLL_MAX_MS));
            check();
            if (count > 0) target.writeBytes(bytes, 0, count);
            return count;
        } finally { Arrays.fill(bytes, (byte)0); }
    }
    @Override protected void doWriteBytes(ByteBuf source) throws Exception {
        check();
        if (!active) throw new IOException("TOR_PEER_UNVERIFIED");
        byte[] bytes = new byte[Math.min(CHUNK_BYTES, source.readableBytes())];
        source.getBytes(source.readerIndex(), bytes);
        try {
            if (!socket.awaitWritable(waitMillis(POLL_MAX_MS))) { check(); return; }
            // Poll may have consumed the remaining lease/deadline. Recheck after it,
            // and serialize this single nonblocking send with local cancellation.
            synchronized (descriptorLock) {
                check();
                int count = socket.write(bytes, bytes.length);
                if (count < 0 || count > bytes.length) throw new IOException("TOR_SOCKET_WRITE");
                source.skipBytes(count);
            }
            check();
        } finally { Arrays.fill(bytes, (byte)0); }
    }
    @Override protected void doWriteFileRegion(FileRegion region) throws IOException { throw new IOException("TOR_FILE_WRITE_FORBIDDEN"); }
    @Override protected void doClose() throws IOException {
        synchronized (descriptorLock) {
            open = false; active = false; aborted = true;
            TorSocketIo owned = socket; socket = null;
            if (owned != null) { owned.shutdown(); owned.close(); }
        }
    }
    private void check() throws IOException {
        if (aborted || !open || !ready.getAsBoolean()) throw new IOException("TOR_REVOKED");
        if (System.nanoTime() >= deadline) throw new IOException("TOR_DEADLINE");
    }
    private int waitMillis(int maximum) throws IOException {
        check();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("TOR_DEADLINE");
        return (int)Math.min(maximum, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
    @Override protected ChannelId newId() { return new PrivateChannelId(); }
    private static final class PrivateChannelId implements ChannelId {
        private final String value = UUID.randomUUID().toString();
        public String asShortText() { return value.substring(0,8); }
        public String asLongText() { return value; }
        public int compareTo(ChannelId other) { return value.compareTo(other.asLongText()); }
        public int hashCode() { return value.hashCode(); }
        public boolean equals(Object other) { return other instanceof PrivateChannelId && value.equals(((PrivateChannelId)other).value); }
        public String toString() { return "TorUnixChannel"; }
    }
}
