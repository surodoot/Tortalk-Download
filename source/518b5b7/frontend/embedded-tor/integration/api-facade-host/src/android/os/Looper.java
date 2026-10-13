package android.os;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Explicit host seam: worker posts are queued, and only the test main thread can dispatch them. */
public final class Looper {
    private static final Thread MAIN_THREAD=Thread.currentThread();
    private static final Looper MAIN=new Looper();
    private final LinkedBlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();
    private Looper() {}
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return Thread.currentThread()==MAIN_THREAD?MAIN:null; }
    void post(Runnable task) { queue.add(task); }
    public static int queued() { return MAIN.queue.size(); }
    public static boolean dispatchOne(long waitMillis) throws InterruptedException {
        requireMain(); Runnable task=MAIN.queue.poll(waitMillis,TimeUnit.MILLISECONDS);
        if(task==null)return false;task.run();return true;
    }
    public static void drain() { requireMain();Runnable task;while((task=MAIN.queue.poll())!=null)task.run(); }
    public static void reset() { requireMain();MAIN.queue.clear(); }
    private static void requireMain() { if(myLooper()!=MAIN)throw new IllegalStateException("HOST_MAIN_THREAD_REQUIRED"); }
}
