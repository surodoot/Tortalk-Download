package android.os;

/** Host-only main-thread identity. Never package this fixture in Android source sets. */
public final class Looper {
    private static final Thread OWNER = Thread.currentThread();
    private static final Looper MAIN = new Looper();
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return Thread.currentThread() == OWNER ? MAIN : null; }
}
