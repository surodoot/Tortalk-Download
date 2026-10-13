package android.app;
import java.util.Collections;
import java.util.List;
public final class ActivityManager {
    public static String processName = android.content.Context.PACKAGE + ":tor";
    public static boolean available = true;
    public static int recordUid = android.os.Process.UID;
    public List<RunningAppProcessInfo> getRunningAppProcesses() {
        if (!available) return null;
        RunningAppProcessInfo item = new RunningAppProcessInfo();
        item.pid = android.os.Process.myPid(); item.uid = recordUid; item.processName = processName;
        return Collections.singletonList(item);
    }
    public static final class RunningAppProcessInfo { public int pid, uid; public String processName; }
}
