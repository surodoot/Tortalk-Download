package android.os;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
/** Never kills any host process. These are synthetic Android identities only. */
public final class Process {
    public static final int UID = 10123, MAIN_PID = 22001, TOR_PID = 22002;
    public static int pid = TOR_PID;
    public static final List<Integer> killRequests = new CopyOnWriteArrayList<>();
    public static int myPid() { return pid; }
    public static int myUid() { return UID; }
    public static void killProcess(int target) { killRequests.add(target); }
}
