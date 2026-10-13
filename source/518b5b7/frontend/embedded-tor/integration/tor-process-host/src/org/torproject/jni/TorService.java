package org.torproject.jni;
import android.app.Service;
import android.content.Context;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import net.freehaven.tor.control.TorControlConnection;
/** Explicit vendor lifecycle double. No JNI loading or Tor execution takes place. */
public class TorService extends Service {
    public static volatile TorControlConnection control = new TorControlConnection();
    public static volatile boolean hangDestroy;
    public static volatile int authenticatedMarker=0;
    public static volatile Thread createThread, destroyThread;
    public static File configFile;
    public static boolean socksPathPrepared, controlPathPrepared, preparedAtCreate, directoryVerified, socketVerified;
    public static String configAtCreate;
    public static final AtomicInteger creates = new AtomicInteger();
    public static final CountDownLatch destroyEntered = new CountDownLatch(1);
    public static final CountDownLatch controlReadEntered = new CountDownLatch(1);
    private static final CountDownLatch neverReleased = new CountDownLatch(1);
    public static File getTorrc(Context context) { return configFile; }
    public TorControlConnection getTorControlConnection() { controlReadEntered.countDown(); return control; }
    public int getSocksPort(){return authenticatedMarker;}
    @Override public void onCreate() {
        createThread = Thread.currentThread();
        preparedAtCreate = socksPathPrepared && controlPathPrepared;
        try { configAtCreate = java.nio.file.Files.readString(configFile.toPath()); }
        catch (java.io.IOException failure) { throw new AssertionError("Missing startup configuration", failure); }
        creates.incrementAndGet();
    }
    @Override public void onDestroy() {
        destroyThread = Thread.currentThread(); destroyEntered.countDown();
        if (hangDestroy) {
            try { neverReleased.await(); } catch (InterruptedException interrupted) { throw new AssertionError("Superclass must not be interrupted"); }
        }
    }
}
