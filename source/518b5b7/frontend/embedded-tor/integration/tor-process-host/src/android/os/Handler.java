package android.os;
public final class Handler {
    public interface Callback { boolean handleMessage(Message message); }
    final Looper looper;
    private final Callback callback;
    public Handler(Looper looper) { this(looper, message -> false); }
    public Handler(Looper looper, Callback callback) { this.looper = looper; this.callback = callback; }
    public boolean post(Runnable action) { looper.execute(action); return true; }
    void dispatch(Message message) { looper.execute(() -> callback.handleMessage(message)); }
}
