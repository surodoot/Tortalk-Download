package android.os;

/** Host main-dispatch double; never executes a worker callback inline. */
public final class Handler {
    private final Looper looper;
    public Handler(Looper looper) { if(looper==null)throw new IllegalArgumentException("HOST_LOOPER_REQUIRED");this.looper=looper; }
    public boolean post(Runnable task) { looper.post(task);return true; }
}
