package android.os;
public final class HandlerThread extends Thread {
    private final Looper looper;
    public HandlerThread(String name) { super(name); setDaemon(true); looper = new Looper(name); }
    public Looper getLooper() { return looper; }
}
