package android.os;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
public final class Looper {
    private static final Looper MAIN = new Looper("host-service-main");
    final ExecutorService queue;
    Looper(String name) {
        queue = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; });
    }
    public static Looper getMainLooper() { return MAIN; }
    public Future<?> execute(Runnable action) { return queue.submit(action); }
    public void idle() throws Exception { execute(() -> { }).get(3, TimeUnit.SECONDS); }
}
