package dev.gyeopdam.embeddedtor;

import java.io.IOException;

/** Narrow byte-I/O seam; production has exactly one Android filesystem-Unix implementation. */
interface TorSocketIo {
    interface Factory { TorSocketIo create(TorSocketEndpoint endpoint) throws IOException; }
    void connect(int timeoutMillis) throws IOException;
    int peerUid() throws IOException;
    int peerPid() throws IOException;
    /** Returns zero for a bounded wait with no bytes, minus one for EOF. */
    int read(byte[] bytes, int length, int waitMillis) throws IOException;
    boolean awaitWritable(int waitMillis) throws IOException;
    /** One nonblocking send; no wait, hidden write-all, or flush loop. */
    int write(byte[] bytes, int length) throws IOException;
    /** Interrupts I/O without releasing the descriptor. Safe against concurrent owner I/O. */
    void shutdown();
    /** Only the I/O owner calls close, after its last syscall has returned. */
    void close() throws IOException;
}
