package dev.gyeopdam.embeddedtor;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;
import android.net.Uri;
import android.view.KeyEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONTokener;

/**
 * Real Android WebView/bootstrap/lifecycle checks, prepared only for a separately authorized
 * fresh-emulator run. No Tor start, network endpoint, account, signing, installation, or launch
 * is performed by merely compiling this source. Run before storage fixtures populate target data.
 * Reflection and evaluateJavascript exist only in this instrumentation source set, never main.
 */
public final class AndroidBridgeRuntimeChecks {
    private static final String PAGE="https://appassets.androidplatform.net/ui/index.html";
    private AndroidBridgeRuntimeChecks() { }
    public static void run(Instrumentation instrumentation) throws Exception {
        Context target=instrumentation.getTargetContext();
        File[] existing=target.getNoBackupFilesDir().listFiles();
        require(existing!=null&&existing.length==0,"FRESH_TARGET_DATA_REQUIRED");
        CountDownLatch startupHeld=new CountDownLatch(1),releaseStartup=new CountDownLatch(1);
        AccountWorkQueue.INSTANCE.execute(()->{startupHeld.countDown();try{releaseStartup.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        require(startupHeld.await(5,TimeUnit.SECONDS),"INITIAL_LOAD_HELD");
        Intent intent=new Intent(target,TorTalkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        TorTalkActivity activity=(TorTalkActivity)instrumentation.startActivitySync(intent);
        try {
            WebView web=read(instrumentation,activity,"web",WebView.class);
            until(()->truth(instrumentation,web,"document.readyState==='complete'&&!!document.querySelector('.welcome-art')"),"PACKAGED_WELCOME");
            until(()->!read(instrumentation,activity,"lastPublished",String.class).isEmpty()&&truth(instrumentation,web,"document.querySelector('[role=\"status\"]').textContent==='기기의 계정 정보를 확인하고 있어요'"),"INITIAL_NATIVE_PORT_ROUND_TRIP");
            require(read(instrumentation,activity,"initializing",Boolean.class),"INITIAL_LOAD_STILL_HELD");
            click(instrumentation,web,"[data-route=\"settings\"]");
            until(()->"settings".equals(read(instrumentation,activity,"route",String.class)),"NAVIGATION_DURING_INITIAL_LOAD");
            require(read(instrumentation,activity,"initializing",Boolean.class),"ROUTE_HANDLED_WHILE_LOAD_HELD");
            releaseStartup.countDown();
            until(()->!read(instrumentation,activity,"initializing",Boolean.class),"INITIAL_STORAGE_CHECK");
            until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);instrumentation.runOnMainSync(()->{try{DeviceRecoveryCoordinator recovery=(DeviceRecoveryCoordinator)field(activity,"deviceRecovery");ready.set(recovery.loaded()&&!recovery.busy()&&!recovery.failed());}catch(Exception failure){throw new AssertionError("RECOVERY_STARTUP_STATE",failure);}});return ready.get();},"INITIAL_RECOVERY_CHECKPOINT_CHECK");
            require("settings".equals(read(instrumentation,activity,"route",String.class)),"INITIAL_LOAD_PRESERVES_NEW_ROUTE");
            require(truth(instrumentation,web,"location.href==='"+PAGE+"'&&/^[a-f0-9]{32}$/.test(document.querySelector('meta[name=\"tortalk-bridge-nonce\"]').content)"),"EXACT_DOCUMENT_AND_NONCE");
            checkInsets(instrumentation,activity,web);
            noTor(instrumentation,target);

            // Begin a synthetic request without network to verify cancellation publishes idle state.
            long routeRequest=syntheticBusy(instrumentation,activity);
            // JS -> native: route is a public UI value, not a credential or private key.
            click(instrumentation,web,"[data-route=\"settings\"]");
            until(()->"settings".equals(read(instrumentation,activity,"route",String.class))&&cancelledIdle(instrumentation,activity,routeRequest),"NATIVE_PORT_INBOUND");
            require(publishedIdle(instrumentation,activity),"ROUTE_PUBLISHES_CANCELLED_STATE");
            syntheticBusy(instrumentation,activity);
            // Native -> JS: the initial packaged script never chooses Privacy on its own.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            until(()->truth(instrumentation,web,"document.querySelector('#main').textContent.indexOf('HONEST BY DESIGN')>=0"),"NATIVE_PORT_OUTBOUND");
            require(publishedIdle(instrumentation,activity),"BACK_PUBLISHES_CANCELLED_STATE");

            // An untrusted script-created event must not replace the real native port.
            evaluate(instrumentation,web,"(function(){var c=new MessageChannel();window.dispatchEvent(new MessageEvent('message',{origin:'',source:null,data:'tortalk-port-v1:'+document.querySelector('meta[name=\"tortalk-bridge-nonce\"]').content,ports:[c.port2]}));c.port1.close();return true;})()");
            click(instrumentation,web,"[data-route=\"settings\"]");
            until(()->"settings".equals(read(instrumentation,activity,"route",String.class)),"UNTRUSTED_BOOTSTRAP_REJECTED");

            String beforeNonce=string(instrumentation,web,"document.querySelector('meta[name=\"tortalk-bridge-nonce\"]').content");
            WebMessagePort old=read(instrumentation,activity,"nativePort",WebMessagePort.class);
            instrumentation.runOnMainSync(()->web.loadUrl(PAGE));
            until(()->!beforeNonce.equals(string(instrumentation,web,"document.querySelector('meta[name=\"tortalk-bridge-nonce\"]')?document.querySelector('meta[name=\"tortalk-bridge-nonce\"]').content:''")),"FRESH_DOCUMENT_NONCE");
            until(()->truth(instrumentation,web,"!!document.querySelector('.settings-profile')"),"RELOADED_NATIVE_STATE");
            require(old!=read(instrumentation,activity,"nativePort",WebMessagePort.class),"FRESH_NATIVE_PORT");
            // A closed old native port may throw or silently discard; neither may update the new page.
            instrumentation.runOnMainSync(()->{try{old.postMessage(new WebMessage("{\"type\":\"state\",\"route\":\"welcome\",\"state\":{\"authenticated\":false}}"));}catch(RuntimeException expected){}});
            SystemClock.sleep(150);
            require(truth(instrumentation,web,"!!document.querySelector('.settings-profile')"),"OLD_PORT_REVOKED");

            // Simulate a duplicate onPageFinished using the real private bootstrap method.
            // This is a test-only reflective call, not a production testing hook.
            Method connect=TorTalkActivity.class.getDeclaredMethod("connectPort");connect.setAccessible(true);
            AtomicReference<Throwable> invokeFailure=new AtomicReference<>();
            instrumentation.runOnMainSync(()->{try{connect.invoke(activity);}catch(Throwable failure){invokeFailure.set(failure);}});
            if(invokeFailure.get()!=null)throw new AssertionError("DUPLICATE_FINISH_CALL",invokeFailure.get());
            until(()->!read(instrumentation,activity,"lastPublished",String.class).isEmpty(),"DUPLICATE_FINISH_READY");
            until(()->truth(instrumentation,web,"!!document.querySelector('.settings-profile')"),"DUPLICATE_FINISH_STATE");
            click(instrumentation,web,"[data-route=\"privacy\"]");
            until(()->"privacy".equals(read(instrumentation,activity,"route",String.class)),"DUPLICATE_FINISH_REBIND");
            click(instrumentation,web,"[data-route=\"settings\"]");
            until(()->"settings".equals(read(instrumentation,activity,"route",String.class)),"SETTINGS_BEFORE_STOP");

            // Deliver a trusted native event with the wrong nonce; the established port must survive.
            String nonce=string(instrumentation,web,"document.querySelector('meta[name=\"tortalk-bridge-nonce\"]').content");
            String wrong=(nonce.charAt(0)=='a'?'b':'a')+nonce.substring(1);
            AtomicReference<WebMessagePort> rejectedPort=new AtomicReference<>();
            instrumentation.runOnMainSync(()->{WebMessagePort[] pair=web.createWebMessageChannel();rejectedPort.set(pair[0]);web.postWebMessage(new WebMessage("tortalk-port-v1:"+wrong,new WebMessagePort[]{pair[1]}),Uri.parse("https://appassets.androidplatform.net"));});
            SystemClock.sleep(150);
            click(instrumentation,web,"[data-route=\"privacy\"]");
            until(()->"privacy".equals(read(instrumentation,activity,"route",String.class)),"WRONG_NATIVE_NONCE_REJECTED");
            instrumentation.runOnMainSync(()->rejectedPort.get().close());
            click(instrumentation,web,"[data-route=\"settings\"]");
            until(()->"settings".equals(read(instrumentation,activity,"route",String.class)),"LIFECYCLE_SETTINGS");

            // Calls real lifecycle methods through Instrumentation; not an OS gesture/screen-lock test.
            instrumentation.runOnMainSync(()->{instrumentation.callActivityOnPause(activity);instrumentation.callActivityOnStop(activity);});
            require(!read(instrumentation,activity,"foreground",Boolean.class),"STOP_REVOKES_FOREGROUND");
            click(instrumentation,web,"[data-action=\"setup\"]");
            SystemClock.sleep(150);
            AlertDialog stoppedDialog=read(instrumentation,activity,"dialog",AlertDialog.class);
            require(stoppedDialog==null||!stoppedDialog.isShowing(),"BACKGROUND_COMMAND_REJECTED");
            instrumentation.runOnMainSync(()->{instrumentation.callActivityOnStart(activity);instrumentation.callActivityOnResume(activity);});
            until(()->truth(instrumentation,web,"!!document.querySelector('.settings-profile')"),"RESUME_REPUBLISHES_STATE");
            click(instrumentation,web,"[data-action=\"setup\"]");
            until(()->{AlertDialog value=read(instrumentation,activity,"dialog",AlertDialog.class);return value!=null&&value.isShowing();},"RESUMED_NATIVE_DIALOG");
            checkDialogScroll(instrumentation,activity);
            instrumentation.runOnMainSync(()->{try{AlertDialog value=(AlertDialog)field(activity,"dialog");if(value!=null)value.dismiss();}catch(Exception failure){throw new AssertionError("DIALOG_CLEANUP",failure);}});
            checkArchiveRetention(instrumentation,activity);
            checkAccountGuards(instrumentation,activity);
            checkDeviceRecoveryDialog(instrumentation,activity,web);
            checkDiagnosticInsets(instrumentation,target);
            noTor(instrumentation,target);
        } finally {
            releaseStartup.countDown();
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }
    /** Developer screen uses the same inset host; no Start/Probe control is activated. */
    private static void checkDiagnosticInsets(Instrumentation i,Context target)throws Exception {
        TorTestActivity diagnostic=(TorTestActivity)i.startActivitySync(new Intent(target,TorTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{until(()->{AtomicReference<Boolean> safe=new AtomicReference<>(false);AtomicReference<Throwable> failure=new AtomicReference<>();i.runOnMainSync(()->{try{
            android.view.ViewGroup root=diagnostic.findViewById(android.R.id.content);require(root.getChildCount()==1&&root.getChildAt(0) instanceof android.widget.FrameLayout,"DIAGNOSTIC_INSET_HOST");
            android.widget.FrameLayout host=(android.widget.FrameLayout)root.getChildAt(0);require(host.getChildCount()==1&&host.getChildAt(0) instanceof android.widget.ScrollView,"DIAGNOSTIC_SCROLL_CONTENT");android.view.View content=host.getChildAt(0);
            boolean geometry=host.getWidth()>0&&content.getLeft()==host.getPaddingLeft()&&content.getTop()==host.getPaddingTop()&&content.getWidth()==host.getWidth()-host.getPaddingLeft()-host.getPaddingRight()&&content.getHeight()==host.getHeight()-host.getPaddingTop()-host.getPaddingBottom();
            if(android.os.Build.VERSION.SDK_INT>=30){android.view.WindowInsets insets=host.getRootWindowInsets();if(insets==null){safe.set(false);return;}Class<?> types=Class.forName("android.view.WindowInsets$Type");int mask=(Integer)types.getMethod("systemBars").invoke(null)|(Integer)types.getMethod("displayCutout").invoke(null);Object occupied=android.view.WindowInsets.class.getMethod("getInsets",int.class).invoke(insets,mask);Class<?> edge=Class.forName("android.graphics.Insets");geometry=geometry&&host.getPaddingLeft()>=edge.getField("left").getInt(occupied)&&host.getPaddingTop()>=edge.getField("top").getInt(occupied)&&host.getPaddingRight()>=edge.getField("right").getInt(occupied)&&host.getPaddingBottom()>=edge.getField("bottom").getInt(occupied);}
            safe.set(geometry);
        }catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError("DIAGNOSTIC_SAFE_LAYOUT",failure.get());return safe.get();},"DIAGNOSTIC_VISIBLE_SAFE_VIEWPORT");noTor(i,target);}finally{i.runOnMainSync(diagnostic::finish);i.waitForIdleSync();}
    }
    /** Synthetic native key display only: never clicks copy/ack or creates a real account. */
    private static void checkDeviceRecoveryDialog(Instrumentation i,TorTalkActivity activity,WebView web)throws Exception {
        String key="gd_acct_"+repeated('B',42)+"A";
        PendingDeviceRecoveryStore.Pending pending=new PendingDeviceRecoveryStore.Pending("http://"+repeated('a',56)+".onion","demo-runtime-account","demo-runtime-device","demo-runtime-recovery","gd_acct_"+repeated('A',43),key,true);
        AtomicReference<Throwable> failure=new AtomicReference<>();i.runOnMainSync(()->{try{
            Method show=TorTalkActivity.class.getDeclaredMethod("showDeviceRecoveryKey",PendingDeviceRecoveryStore.Pending.class);show.setAccessible(true);show.invoke(activity,pending);
            AlertDialog dialog=(AlertDialog)field(activity,"dialog");require(dialog!=null&&dialog.isShowing(),"RECOVERY_NATIVE_DIALOG");
            require((dialog.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0,"RECOVERY_CAPTURE_PROTECTION");
            require(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),"RECOVERY_ACK_REQUIRES_UNCHECKED_CONSENT");
            android.widget.ScrollView scroll=formScroller(dialog.getWindow().getDecorView());require(scroll!=null,"RECOVERY_SCROLLABLE_FORM");
            android.widget.LinearLayout fields=(android.widget.LinearLayout)scroll.getChildAt(0);android.widget.TextView text=(android.widget.TextView)fields.getChildAt(0);
            require(!text.isSaveEnabled()&&text.getImportantForAutofill()==android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,"RECOVERY_NO_SAVE_OR_AUTOFILL");
            require(text.getText().toString().contains(key),"RECOVERY_EXPLICIT_NATIVE_KEY_DISPLAY");
            require(!((String)field(activity,"lastPublished")).contains(key),"RECOVERY_KEY_NEVER_IN_NATIVE_PUBLIC_STATE");
        }catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError("RECOVERY_DIALOG_POLICY",failure.get());
        require(!string(i,web,"document.documentElement.textContent").contains(key),"RECOVERY_KEY_NEVER_IN_DOM");
        i.runOnMainSync(()->{try{((AlertDialog)field(activity,"dialog")).dismiss();}catch(Exception e){throw new AssertionError("RECOVERY_DIALOG_CLEANUP",e);}});
    }
    /** Verifies the production retention snapshot/restoration path; not an OS rotation gesture test. */
    private static void checkArchiveRetention(Instrumentation i,TorTalkActivity activity)throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();i.runOnMainSync(()->{try{
            String oldRoute=(String)field(activity,"route");boolean navigated=(Boolean)field(activity,"startupNavigated");
            setField(activity,"startupNavigated",false);setField(activity,"route","chat");setField(activity,"selectedRoom","demo-room-archive-fixture");
            setField(activity,"localHistoryMode",true);setField(activity,"localHistoryPeer","demo-peer-archive-fixture");setField(activity,"localHistoryBefore","synthetic-local-cursor");
            Object retained=activity.onRetainNonConfigurationInstance();
            setField(activity,"route","welcome");setField(activity,"selectedRoom","");setField(activity,"localHistoryMode",false);setField(activity,"localHistoryPeer","");setField(activity,"localHistoryBefore","");
            Method restore=TorTalkActivity.class.getDeclaredMethod("restoreRetainedView",retained.getClass());restore.setAccessible(true);restore.invoke(activity,retained);
            require((Boolean)field(activity,"localHistoryMode")&&"chat".equals(field(activity,"route")),"ARCHIVE_MODE_RETAINED");
            require("demo-peer-archive-fixture".equals(field(activity,"localHistoryPeer"))&&"synthetic-local-cursor".equals(field(activity,"localHistoryBefore")),"ARCHIVE_POSITION_RETAINED");
            Method allow=TorTalkActivity.class.getDeclaredMethod("allowPeerTraffic");allow.setAccessible(true);require(!((Boolean)allow.invoke(activity)),"RESTORED_ARCHIVE_DENIES_PEER_TRAFFIC");
            setField(activity,"startupNavigated",true);setField(activity,"route","settings");setField(activity,"localHistoryMode",false);restore.invoke(activity,retained);
            require("settings".equals(field(activity,"route"))&&!((Boolean)field(activity,"localHistoryMode")),"NEW_ROUTE_OVERRIDES_ARCHIVE_RESTORE");
            invokeUi(activity,"leaveLocalHistory");setField(activity,"selectedRoom","");setField(activity,"route",oldRoute);setField(activity,"startupNavigated",navigated);
        }catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError("ARCHIVE_VIEW_RETENTION",failure.get());
    }
    private static void setField(Object value,String name,Object next)throws Exception {Field field=TorTalkActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(value,next);}
    private static void invokeUi(TorTalkActivity activity,String name)throws Exception {Method method=TorTalkActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(activity);}
    private static String repeated(char value,int length){char[] chars=new char[length];java.util.Arrays.fill(chars,value);return new String(chars);}
    private static void checkAccountGuards(Instrumentation i,TorTalkActivity activity)throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch diskHeld=new CountDownLatch(1),releaseDisk=new CountDownLatch(1),diskDone=new CountDownLatch(1);
        AccountWorkQueue.INSTANCE.execute(()->{diskHeld.countDown();try{releaseDisk.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        require(diskHeld.await(5,TimeUnit.SECONDS),"LOGOUT_DISK_HELD");
        try{i.runOnMainSync(()->{try{
            String origin="http://"+repeated('a',56)+".onion";
            AppSessionStore.Session signedIn=new AppSessionStore.Session(origin,"demo-account-b","","","","gd_sess_"+repeated('A',43));
            PendingAccountStore.Pending pending=new PendingAccountStore.Pending(origin,"demo-account-"+repeated('a',32),"gd_acct_"+repeated('A',43));
            setField(activity,"session",signedIn);setField(activity,"persistSession",true);setField(activity,"pendingEnrollment",pending);
            invokeUi(activity,"resumePendingDialog");
            require(field(activity,"session")==signedIn,"PENDING_CANNOT_REPLACE_OTHER_ACCOUNT");
            AlertDialog existing=(AlertDialog)field(activity,"dialog");require(existing==null||!existing.isShowing(),"NO_CROSS_ACCOUNT_RESUME_DIALOG");
            setField(activity,"session",null);invokeUi(activity,"resumePendingDialog");
            AlertDialog resume=(AlertDialog)field(activity,"dialog");resume.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            require(EmbeddedTorController.get(activity).phase()==TorReadinessGate.Phase.STOPPED,"WRONG_ORIGIN_RESUME_MUST_NOT_START_TOR");
            require(field(activity,"session")==null,"WRONG_ORIGIN_CANNOT_AUTHENTICATE");
            ((AlertDialog)field(activity,"dialog")).dismiss();setField(activity,"pendingEnrollment",null);
            setField(activity,"session",signedIn);setField(activity,"persistSession",true);
            invokeUi(activity,"logoutDialog");((AlertDialog)field(activity,"dialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            require(field(activity,"session")==null&&!((Boolean)field(activity,"persistSession")),"LOGOUT_REVOKES_LOCAL_STATE_BEFORE_DISK");
            require(!AccountWorkQueue.restorationAllowed(),"LOGOUT_FENCE_PRECEDES_DISK");
            i.callActivityOnPause(activity);i.callActivityOnStop(activity);
        }catch(Throwable e){failure.set(e);}});}finally{releaseDisk.countDown();}
        if(failure.get()!=null)throw new AssertionError("ACCOUNT_LIFECYCLE_GUARDS",failure.get());
        AccountWorkQueue.INSTANCE.execute(diskDone::countDown);require(diskDone.await(5,TimeUnit.SECONDS),"LOGOUT_DISK_COMPLETES_AFTER_STOP");
        require(AccountWorkQueue.restorationAllowed(),"SUCCESSFUL_DELETE_RELEASES_FENCE");
        i.runOnMainSync(()->{i.callActivityOnStart(activity);i.callActivityOnResume(activity);});
        require(read(i,activity,"session",AppSessionStore.Session.class)==null,"STOP_CANNOT_UNDO_LOCAL_LOGOUT");
    }
    private static long syntheticBusy(Instrumentation i,TorTalkActivity activity)throws Exception {
        AtomicReference<Long> request=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();i.runOnMainSync(()->{try{
            TorTalkState state=(TorTalkState)field(activity,"flow");long operation=state.begin();require(operation>0,"SYNTHETIC_REQUEST_BEGIN");request.set(operation);
            Method publish=TorTalkActivity.class.getDeclaredMethod("publish",String.class);publish.setAccessible(true);publish.invoke(activity,new Object[]{null});
        }catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError("SYNTHETIC_BUSY",failure.get());
        require(!publishedIdle(i,activity),"SYNTHETIC_BUSY_PUBLISHED");return request.get();
    }
    private static boolean cancelledIdle(Instrumentation i,TorTalkActivity activity,long request)throws Exception {
        AtomicReference<Boolean> cancelled=new AtomicReference<>(false);AtomicReference<Throwable> failure=new AtomicReference<>();i.runOnMainSync(()->{try{TorTalkState flow=(TorTalkState)field(activity,"flow");cancelled.set(!flow.owns(request)&&!flow.busy);}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError("REQUEST_CANCELLATION_STATE",failure.get());return cancelled.get()&&publishedIdle(i,activity);
    }
    private static boolean publishedIdle(Instrumentation i,TorTalkActivity activity)throws Exception {
        org.json.JSONObject state=new org.json.JSONObject(read(i,activity,"lastPublished",String.class)).getJSONObject("state");
        return !state.getBoolean("busy")&&state.getJSONArray("messages").length()==0;
    }
    private static android.widget.ScrollView formScroller(android.view.View view){
        if(view instanceof android.widget.ScrollView){android.widget.ScrollView scroll=(android.widget.ScrollView)view;
            if(scroll.getChildCount()==1&&scroll.getChildAt(0) instanceof android.widget.LinearLayout)return scroll;}
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int n=0;n<group.getChildCount();n++){android.widget.ScrollView result=formScroller(group.getChildAt(n));if(result!=null)return result;}}
        return null;
    }
    private static void checkDialogScroll(Instrumentation i,TorTalkActivity activity)throws Exception {
        AlertDialog dialog=read(i,activity,"dialog",AlertDialog.class);
        AtomicReference<android.widget.ScrollView> scroller=new AtomicReference<>();
        AtomicReference<android.widget.TextView> last=new AtomicReference<>();
        i.runOnMainSync(()->{
            require((dialog.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0,"DIALOG_CAPTURE_PROTECTION");
            require((dialog.getWindow().getAttributes().softInputMode&android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)==android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,"DIALOG_IME_RESIZE");
            android.widget.ScrollView scroll=formScroller(dialog.getWindow().getDecorView());require(scroll!=null,"NATIVE_FORM_SCROLLER");scroller.set(scroll);
            android.widget.LinearLayout form=(android.widget.LinearLayout)scroll.getChildAt(0);
            for(int n=0;n<40;n++){android.widget.TextView text=new android.widget.TextView(activity);text.setText("Synthetic layout row");form.addView(text);last.set(text);}
        });
        until(()->{AtomicReference<Boolean> value=new AtomicReference<>(false);i.runOnMainSync(()->{
            android.widget.ScrollView scroll=scroller.get();value.set(scroll.getHeight()>0&&scroll.canScrollVertically(1)
                &&scroll.getHeight()<=(int)(activity.getResources().getDisplayMetrics().heightPixels*0.45f));
            scroll.fullScroll(android.view.View.FOCUS_DOWN);
        });return value.get();},"BOUNDED_NATIVE_FORM_SCROLLS");
        until(()->{AtomicReference<Boolean> visible=new AtomicReference<>(false);i.runOnMainSync(()->{
            android.graphics.Rect bounds=new android.graphics.Rect();visible.set(last.get().getGlobalVisibleRect(bounds)&&bounds.height()>0);
        });return visible.get();},"LAST_NATIVE_FORM_ROW_REACHABLE");
    }
    /** API30 reflection keeps this test source API26-compilable; execution remains required. */
    private static void checkInsets(Instrumentation i,TorTalkActivity activity,WebView web)throws Exception {
        if(android.os.Build.VERSION.SDK_INT<30)return;
        android.widget.FrameLayout host=read(i,activity,"contentHost",android.widget.FrameLayout.class);
        until(()->{AtomicReference<Boolean> valid=new AtomicReference<>(false);i.runOnMainSync(()->{
            valid.set(host.getWidth()>0&&web.getWidth()==host.getWidth()-host.getPaddingLeft()-host.getPaddingRight()
                &&web.getHeight()==host.getHeight()-host.getPaddingTop()-host.getPaddingBottom()
                &&web.getLeft()==host.getPaddingLeft()&&web.getTop()==host.getPaddingTop());
        });return valid.get();},"REAL_WEBVIEW_SAFE_VIEWPORT");
        AtomicReference<Throwable> failure=new AtomicReference<>();
        i.runOnMainSync(()->{try {
            Class<?> types=Class.forName("android.view.WindowInsets$Type");
            Class<?> edge=Class.forName("android.graphics.Insets");
            Class<?> builderType=Class.forName("android.view.WindowInsets$Builder");
            int bars=(Integer)types.getMethod("systemBars").invoke(null);
            int cutout=(Integer)types.getMethod("displayCutout").invoke(null);
            int ime=(Integer)types.getMethod("ime").invoke(null);
            Method of=edge.getMethod("of",int.class,int.class,int.class,int.class);
            Method set=builderType.getMethod("setInsets",int.class,edge);
            for(int bottom:new int[]{211,23,0}) {
                Object builder=builderType.getConstructor().newInstance();
                Object safe=of.invoke(null,bottom==0?0:11,bottom==0?0:29,bottom==0?0:17,bottom==0?0:23);
                set.invoke(builder,bars|cutout,safe);
                set.invoke(builder,ime,of.invoke(null,0,0,0,bottom==211?211:0));
                android.view.WindowInsets incoming=(android.view.WindowInsets)builderType.getMethod("build").invoke(builder);
                android.view.WindowInsets forwarded=host.dispatchApplyWindowInsets(incoming);
                require(host.getPaddingBottom()==bottom,"IME_MAX_UNION_AND_HIDE");
                require(host.getPaddingLeft()==(bottom==0?0:11)&&host.getPaddingTop()==(bottom==0?0:29)
                    &&host.getPaddingRight()==(bottom==0?0:17),"BARS_CUTOUT_SAFE_PADDING");
                require(!forwarded.isConsumed(),"INSETS_MUST_KEEP_DISPATCHING");
                Object remaining=android.view.WindowInsets.class.getMethod("getInsets",int.class).invoke(forwarded,bars|cutout|ime);
                for(String side:new String[]{"left","top","right","bottom"})require(edge.getField(side).getInt(remaining)==0,"NO_DOUBLE_INSETS");
            }
        }catch(Throwable e){failure.set(e);}finally{host.requestApplyInsets();}});
        if(failure.get()!=null)throw new AssertionError("INSET_GEOMETRY",failure.get());
    }
    private interface Check { boolean ready() throws Exception; }
    private static void until(Check check,String code)throws Exception {long deadline=SystemClock.uptimeMillis()+20000;while(!check.ready()){if(SystemClock.uptimeMillis()>=deadline)throw new AssertionError(code);SystemClock.sleep(50);}}
    private static void require(boolean condition,String code){if(!condition)throw new AssertionError(code);}
    private static Object field(Object value,String name)throws Exception {Field field=TorTalkActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    private static <T>T read(Instrumentation i,Object value,String name,Class<T> type)throws Exception {AtomicReference<Object> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();i.runOnMainSync(()->{try{result.set(field(value,name));}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError("READ_PUBLIC_UI_STATE",failure.get());return type.cast(result.get());}
    private static String evaluate(Instrumentation i,WebView web,String script)throws Exception {CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();i.runOnMainSync(()->{try{web.evaluateJavascript(script,value->{result.set(value);latch.countDown();});}catch(Throwable e){failure.set(e);latch.countDown();}});require(latch.await(5,TimeUnit.SECONDS),"JAVASCRIPT_TIMEOUT");if(failure.get()!=null)throw new AssertionError("JAVASCRIPT_EXECUTION",failure.get());return result.get();}
    private static boolean truth(Instrumentation i,WebView web,String expression)throws Exception {return "true".equals(evaluate(i,web,"Boolean("+expression+")"));}
    private static String string(Instrumentation i,WebView web,String expression)throws Exception {Object result=new JSONTokener(evaluate(i,web,expression)).nextValue();return result instanceof String?(String)result:"";}
    private static void click(Instrumentation i,WebView web,String selector)throws Exception {require(truth(i,web,"(function(){var element=document.querySelector('"+selector+"');if(!element)return false;element.click();return true;})()"),"PUBLIC_UI_CONTROL_MISSING");}
    private static void noTor(Instrumentation i,Context context){AtomicReference<TorReadinessGate.Phase> phase=new AtomicReference<>();i.runOnMainSync(()->phase.set(EmbeddedTorController.get(context).phase()));require(phase.get()==TorReadinessGate.Phase.STOPPED,"TEST_MUST_NOT_START_TOR");}
}
