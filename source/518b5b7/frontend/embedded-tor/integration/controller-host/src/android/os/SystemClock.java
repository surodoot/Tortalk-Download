package android.os;

/** Virtual monotonic Android clock. It does not change the production worker's JVM scheduler. */
public final class SystemClock {
    private static volatile long now = 1_000;
    public static long elapsedRealtime() { return now; }
    public static void advance(long millis) {
        if (Looper.myLooper() != Looper.getMainLooper() || millis < 0) throw new IllegalArgumentException();
        now += millis;
    }
}
