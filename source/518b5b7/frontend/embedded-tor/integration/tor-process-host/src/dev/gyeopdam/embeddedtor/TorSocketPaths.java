package dev.gyeopdam.embeddedtor;

import android.content.Context;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Explicit host filesystem seam: records path lifecycle; no Unix socket or Android filesystem API. */
final class TorSocketPaths {
    static final String PATH = "/data/user/0/dev.gyeopdam.embeddedtor.experiment/files/tor-socks/s";
    static final List<String> events = new CopyOnWriteArrayList<>();
    static volatile boolean rejectPrepare, rejectControlPrepare, rejectDirectoryVerify, rejectVerify;
    static String socksPath(Context context) { return PATH; }
    static void prepare(Context context, int uid) throws IOException {
        events.add("prepare:" + uid);
        if (rejectPrepare) throw new IOException("HOST_PATH_REJECTED");
        org.torproject.jni.TorService.socksPathPrepared = true;
    }
    static void prepareControl(java.io.File torrc, int uid) throws IOException {
        events.add("prepareControl:" + uid);
        if (rejectControlPrepare) throw new IOException("HOST_CONTROL_PATH_REJECTED");
        org.torproject.jni.TorService.controlPathPrepared = true;
    }
    static void verifyDirectoryForPath(String path, int uid) throws IOException {
        events.add("verifyDirectory:" + uid);
        if (rejectDirectoryVerify || !PATH.equals(path)) throw new IOException("HOST_DIRECTORY_REJECTED");
        org.torproject.jni.TorService.directoryVerified = true;
        org.torproject.jni.TorService.control.events.add("verify-directory");
    }
    static void verify(TorSocketEndpoint endpoint) throws IOException {
        events.add("verify:" + endpoint.pid);
        org.torproject.jni.TorService.control.events.add("verify-socket");
        if (rejectVerify || !PATH.equals(endpoint.path)) throw new IOException("HOST_ENDPOINT_REJECTED");
        org.torproject.jni.TorService.socketVerified = true;
    }
}
