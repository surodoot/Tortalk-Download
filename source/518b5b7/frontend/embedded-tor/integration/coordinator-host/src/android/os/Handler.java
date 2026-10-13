package android.os;
import java.util.concurrent.*;
public final class Handler  {
    public static final BlockingQueue<Runnable> QUEUE=new LinkedBlockingQueue<>();
    public Handler(Looper l) {
    }
    public boolean post(Runnable r) {
        QUEUE.add(r);
        return true;
    }
    public static void pump() throws Exception  {
        Runnable r=QUEUE.poll(10,TimeUnit.SECONDS);
        if(r==null)throw new AssertionError("main queue timed out");
        r.run();
    }
}
