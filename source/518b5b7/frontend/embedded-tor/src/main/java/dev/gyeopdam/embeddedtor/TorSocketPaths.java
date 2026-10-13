package dev.gyeopdam.embeddedtor;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.IOException;

/** No path is accepted from a control reply, Intent, WebView, or remote server. */
final class TorSocketPaths {
    private TorSocketPaths() { }
    static String socksPath(Context context) {
        try {
            String path = new File(new File(context.getFilesDir().getCanonicalFile(), "tor-socks"), "s").getPath();
            TorSocketEndpoint.validatePath(path);
            return path;
        } catch (IOException invalid) { throw new IllegalStateException("TOR_SOCKET_PATH_UNAVAILABLE"); }
    }

    static void prepare(Context context, int uid) throws IOException {
        File socket = new File(socksPath(context));
        File directory = socket.getParentFile();
        try {
            StructStat base = Os.lstat(directory.getParent());
            if (!OsConstants.S_ISDIR(base.st_mode) || base.st_uid != uid) fail();
            try { Os.mkdir(directory.getPath(), 0700); }
            catch (ErrnoException exists) { if (exists.errno != OsConstants.EEXIST) throw exists; }
            verifyDirectory(directory.getPath(), uid);
            // A new service incarnation starts only after the retained old Binder is dead.
            // Never unlink during stop or deferred cleanup, which could remove a replacement.
            try {
                verifySocket(socket.getPath(), uid);
                if (!socket.delete()) fail();
            } catch (ErrnoException absent) { if (absent.errno != OsConstants.ENOENT) throw absent; }
        } catch (ErrnoException invalid) { throw new IOException("TOR_SOCKET_FILESYSTEM", invalid); }
    }

    static void prepareControl(File torrc, int uid) throws IOException {
        // Preserve the vendor's existing private control path. Tighten only its app-owned
        // directories before JNI starts; no control filename or cookie comes from a reply.
        File root = torrc.getParentFile();
        try {
            StructStat parent = Os.lstat(root.getPath());
            if (!OsConstants.S_ISDIR(parent.st_mode) || parent.st_uid != uid) fail();
            Os.chmod(root.getPath(), 0700);
            File data = new File(root, "data");
            try { Os.mkdir(data.getPath(), 0700); }
            catch (ErrnoException exists) { if (exists.errno != OsConstants.EEXIST) throw exists; }
            StructStat contents = Os.lstat(data.getPath());
            if (!OsConstants.S_ISDIR(contents.st_mode) || contents.st_uid != uid) fail();
            Os.chmod(data.getPath(), 0700);
            verifyDirectory(root.getPath(), uid); verifyDirectory(data.getPath(), uid);
        } catch (ErrnoException invalid) { throw new IOException("TOR_CONTROL_FILESYSTEM", invalid); }
    }

    static void verify(TorSocketEndpoint endpoint) throws IOException {
        try {
            verifyDirectory(new File(endpoint.path).getParent(), endpoint.uid);
            verifySocket(endpoint.path, endpoint.uid);
        } catch (ErrnoException invalid) { throw new IOException("TOR_SOCKET_FILESYSTEM", invalid); }
    }
    static void verifyDirectoryForPath(String socksPath, int uid) throws IOException {
        TorSocketEndpoint.validatePath(socksPath);
        try { verifyDirectory(new File(socksPath).getParent(), uid); }
        catch (ErrnoException invalid) { throw new IOException("TOR_SOCKET_FILESYSTEM", invalid); }
    }
    private static void verifyDirectory(String path, int uid) throws ErrnoException, IOException {
        StructStat info = Os.lstat(path);
        if (!OsConstants.S_ISDIR(info.st_mode) || info.st_uid != uid || (info.st_mode & 0777) != 0700) fail();
    }
    private static void verifySocket(String path, int uid) throws ErrnoException, IOException {
        StructStat info = Os.lstat(path);
        if (!OsConstants.S_ISSOCK(info.st_mode) || info.st_uid != uid
                || (info.st_mode & 0077) != 0 || (info.st_mode & 0200) == 0) fail();
    }
    private static void fail() throws IOException { throw new IOException("TOR_SOCKET_FILESYSTEM"); }
}
