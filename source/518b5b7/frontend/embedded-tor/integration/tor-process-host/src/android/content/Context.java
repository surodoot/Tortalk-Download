package android.content;
import android.app.ActivityManager;
import android.content.pm.ApplicationInfo;
public class Context {
    public static final String ACTIVITY_SERVICE = "activity";
    public static final String PACKAGE = "dev.gyeopdam.embeddedtor.experiment";
    public static int applicationUid = android.os.Process.UID;
    public String getPackageName() { return PACKAGE; }
    public Object getSystemService(String name) { return new ActivityManager(); }
    public ApplicationInfo getApplicationInfo() {
        ApplicationInfo result = new ApplicationInfo(); result.uid = applicationUid; return result;
    }
}
