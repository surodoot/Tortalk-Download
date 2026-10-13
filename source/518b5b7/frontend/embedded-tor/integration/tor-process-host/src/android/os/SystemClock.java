package android.os;
public final class SystemClock {
    private static volatile long now = 1000;
    public static long elapsedRealtime() { return now; }
    public static void set(long value) { now = value; }
}
