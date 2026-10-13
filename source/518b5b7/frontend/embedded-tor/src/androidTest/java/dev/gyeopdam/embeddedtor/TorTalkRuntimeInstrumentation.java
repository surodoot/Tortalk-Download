package dev.gyeopdam.embeddedtor;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;

/** Explicitly invoked, isolated test APK only. Never runs as part of the user application. */
public final class TorTalkRuntimeInstrumentation extends Instrumentation {
    private Bundle arguments;
    private int passed;
    interface Check { void run() throws Exception; }
    @Override public void onCreate(Bundle args) { arguments=args; super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            require(arguments!=null&&"true".equals(arguments.getString("syntheticOnly")),"EXPLICIT_SYNTHETIC_TEST_REQUIRED");
            require(getTargetContext().getPackageName().equals("dev.gyeopdam.embeddedtor.experiment"),"WRONG_TARGET_PACKAGE");
            // Never launch the native UI over a user's existing local account/configuration.
            require(empty(getTargetContext().getNoBackupFilesDir()),"FRESH_TARGET_INSTALL_REQUIRED");
            require(empty(new File(getTargetContext().getApplicationInfo().dataDir,"shared_prefs")),"FRESH_TARGET_INSTALL_REQUIRED");
            // This helper requires an absent files/tor-socks path before any UI/service group.
            check("android_unix_socket_boundary",()->AndroidUnixSocketRuntimeChecks.run(getTargetContext()));
            check("offline_local_archive",()->AndroidArchiveRuntimeChecks.run(this));
            check("packaged_webview_bridge",()->AndroidBridgeRuntimeChecks.run(this));
            check("android_keystore_atomicfile",()->AndroidStorageRuntimeChecks.run(getTargetContext()));
            check("official_signal_android_jni",AndroidSignalRuntimeChecks::run);
            check("offline_contact_qr",AndroidQrRuntimeChecks::run);
            check("offline_tor_process_lifetime",()->AndroidTorProcessRuntimeChecks.run(this));
            result.putString("stream","PASS 7 Android runtime groups\n");
            result.putString("unix_socket_scope",AndroidUnixSocketRuntimeChecks.SCOPE);
            result.putString("unix_socket_checks",AndroidUnixSocketRuntimeChecks.CHECKS);
            result.putInt("runtime_groups_passed",passed);
            result.putInt("android_api",android.os.Build.VERSION.SDK_INT);
            finish(Activity.RESULT_OK,result);
        } catch (Throwable failure) {
            // Avoid outputting exception messages, plaintext test messages or key material.
            String code="RUNTIME_CHECK_FAILED";
            if(failure instanceof KeystoreBlobStore.StorageException)code=((KeystoreBlobStore.StorageException)failure).code.name();
            else if(failure instanceof LinkageError)code="ANDROID_NATIVE_LINKAGE_FAILED";
            else if(failure instanceof AssertionError&&failure.getMessage()!=null&&failure.getMessage().matches("[A-Z_]{1,80}"))code=failure.getMessage();
            result.putString("stream","FAIL Android runtime checks: "+code+"\n");
            result.putInt("runtime_groups_passed",passed);
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private void check(String name,Check check)throws Exception {
        Bundle progress=new Bundle();progress.putString("stream","RUN "+name+"\n");sendStatus(0,progress);
        check.run();passed++;progress.putString("stream","PASS "+name+"\n");sendStatus(0,progress);
    }
    private static boolean empty(File directory) { String[] files=directory.list();return !directory.exists()||files!=null&&files.length==0; }
    static void require(boolean condition,String fixedCode) { if(!condition)throw new AssertionError(fixedCode); }
}
