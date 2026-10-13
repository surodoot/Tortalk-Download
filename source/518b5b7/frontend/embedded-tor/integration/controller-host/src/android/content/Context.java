package android.content;
import android.os.Binder;
import android.os.Looper;
import android.os.Message;
import java.io.File;
import java.util.*;

/** Explicit started/non-auto-created service and remote Binder doubles, no Android or Tor execution. */
public class Context {
 public static final int BIND_AUTO_CREATE=1;
 public final File directory;
 private final Context application;
 public int starts,stops,binds,unbinds;
 public boolean bindResult=true,throwOnStart,throwOnBind;
 public ServiceConnection connection;
 public Binder remote;
 public final List<Message> requests=new ArrayList<>();
 private final Set<ServiceConnection> bindings=new HashSet<>();
 public Context(File directory){this.directory=directory;application=this;}
 public Context(Context application){directory=application.directory;this.application=application;}
 public Context getApplicationContext(){return application;}
 public String getPackageName(){return "dev.gyeopdam.embeddedtor.experiment";}
 public ComponentName startService(Intent intent){check(intent);starts++;if(throwOnStart)throw new IllegalStateException("Synthetic start refusal");remote=new Binder(requests::add);return new ComponentName();}
 public boolean stopService(Intent intent){check(intent);stops++;return true;}
 public boolean bindService(Intent intent,ServiceConnection connection,int flags){check(intent);if(flags!=0)throw new AssertionError("AUTO_CREATE forbidden");binds++;this.connection=connection;if(throwOnBind)throw new IllegalStateException("Synthetic bind refusal");if(bindResult)bindings.add(connection);return bindResult;}
 public void unbindService(ServiceConnection connection){if(!bindings.remove(connection))throw new AssertionError("Unknown/duplicate unbind");unbinds++;}
 public void connect(){connection.onServiceConnected(new ComponentName(),remote);}
 private void check(Intent intent){if(Looper.myLooper()!=Looper.getMainLooper()||this!=application||intent.context!=this||!intent.target.getName().equals("dev.gyeopdam.embeddedtor.TorProcessService"))throw new AssertionError("Unexpected service access");}
}
