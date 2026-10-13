package android.os;

import java.util.PriorityQueue;

/** Queued main callbacks with explicit virtual-time delivery; worker callbacks never run inline. */
public final class Handler {
    private static final PriorityQueue<Entry> QUEUE = new PriorityQueue<>();
    private static long sequence;
    private static final class Entry implements Comparable<Entry> {
        final Handler owner; final Runnable task; final long at; final long order;
        Entry(Handler owner, Runnable task, long at) {
            this.owner = owner; this.task = task; this.at = at; this.order = sequence++;
        }
        public int compareTo(Entry other) {
            int time = Long.compare(at, other.at);
            return time != 0 ? time : Long.compare(order, other.order);
        }
    }
    public interface Callback {boolean handleMessage(Message message);}
    private Callback callback;
    public Handler(Looper looper,Callback callback){this(looper);this.callback=callback;}
    public void dispatchMessage(Message message){if(callback!=null)callback.handleMessage(message);}
    public Handler(Looper looper) {
        if (looper != Looper.getMainLooper()) throw new IllegalArgumentException("Main only");
    }
    public boolean post(Runnable task) { return postDelayed(task, 0); }
    public boolean postDelayed(Runnable task, long delay) {
        synchronized (QUEUE) { QUEUE.add(new Entry(this, task, SystemClock.elapsedRealtime() + delay)); }
        return true;
    }
    public void removeCallbacks(Runnable task) {
        synchronized (QUEUE) { QUEUE.removeIf(entry -> entry.owner == this && entry.task == task); }
    }
    public static void drain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new AssertionError("Drain on main only");
        for (int count = 0; count < 10_000; count++) {
            Entry entry;
            synchronized (QUEUE) {
                entry = QUEUE.peek();
                if (entry == null || entry.at > SystemClock.elapsedRealtime()) return;
                QUEUE.remove();
            }
            entry.task.run();
        }
        throw new AssertionError("Main callback queue did not settle");
    }
    public static int pending() { synchronized (QUEUE) { return QUEUE.size(); } }
}
