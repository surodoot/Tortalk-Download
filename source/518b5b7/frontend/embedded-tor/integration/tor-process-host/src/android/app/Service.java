package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.util.concurrent.atomic.AtomicInteger;
/** Explicit lifecycle double: stopSelf records a request; the test drives destruction. */
public class Service extends Context {
    public static final int START_NOT_STICKY = 2;
    public final AtomicInteger stopRequests = new AtomicInteger();
    public void onCreate() { }
    public int onStartCommand(Intent intent, int flags, int startId) { return 1; }
    public IBinder onBind(Intent intent) { return null; }
    public boolean onUnbind(Intent intent) { return false; }
    public void onDestroy() { }
    public void onTaskRemoved(Intent intent) { }
    public void stopSelf() { stopRequests.incrementAndGet(); }
}
