package dev.gyeopdam.embeddedtor;

import java.net.SocketAddress;

/** App-derived filesystem name and the Binder incarnation's expected kernel peer identity. */
final class TorSocketEndpoint extends SocketAddress {
    final String path;
    final int uid, pid;

    TorSocketEndpoint(String path, int uid, int pid) {
        validatePath(path);
        if (uid < 0 || pid <= 0) throw new IllegalArgumentException("INVALID_TOR_PEER");
        this.path = path; this.uid = uid; this.pid = pid;
    }

    static void validatePath(String path) {
        // Linux sockaddr_un has 108 bytes including NUL. App paths are deliberately ASCII.
        if (path == null || !path.matches("/[A-Za-z0-9_./-]{1,105}")
                || path.contains("//") || path.contains("/../") || path.contains("/./")
                || path.endsWith("/") || path.endsWith("/..") || path.endsWith("/.")) {
            throw new IllegalArgumentException("INVALID_TOR_SOCKET_PATH");
        }
    }

    String controlListener() { return "\"unix:" + path + "\""; }
    boolean matches(int peerUid, int peerPid) { return uid == peerUid && pid == peerPid; }
    @Override public boolean equals(Object value) {
        if (!(value instanceof TorSocketEndpoint)) return false;
        TorSocketEndpoint other = (TorSocketEndpoint)value;
        return uid == other.uid && pid == other.pid && path.equals(other.path);
    }
    @Override public int hashCode() { return 31 * (31 * path.hashCode() + uid) + pid; }
    @Override public String toString() { return "TorUnixEndpoint"; }
}
