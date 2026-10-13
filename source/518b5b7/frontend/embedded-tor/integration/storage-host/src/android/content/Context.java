package android.content;

import java.io.File;

/** HOST TEST DOUBLE: only the app-private context surface used by production storage. */
public abstract class Context {
    public abstract Context getApplicationContext();
    public abstract String getPackageName();
    public abstract File getNoBackupFilesDir();
}
