package dev.gyeopdam.embeddedtor;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real offline Tor-process lifetime check, prepared only; never sends network-enabling START IPC. */
final class AndroidTorProcessRuntimeChecks {
    static void run(Instrumentation instrumentation)throws Exception {
        Context context=instrumentation.getTargetContext();
        int mainPid=Process.myPid();
        TorTalkActivity activity=(TorTalkActivity)instrumentation.startActivitySync(
                new Intent(context,TorTalkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.waitForIdleSync();
            // Without an owner claim, the remote's independent startup deadline must terminate it.
            incarnation(instrumentation,context,false);
            require(Process.myPid()==mainPid,"MAIN_PROCESS_SURVIVES_UNCLAIMED_STOP");
            // A separate explicit incarnation must also die after a normal service stop.
            incarnation(instrumentation,context,true);
            require(Process.myPid()==mainPid,"MAIN_PROCESS_SURVIVES_EXPLICIT_STOP");
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }
    private static void incarnation(Instrumentation instrumentation,Context context,boolean explicitStop)throws Exception {
        CountDownLatch connected=new CountDownLatch(1),dead=new CountDownLatch(1);
        AtomicReference<IBinder> exact=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicBoolean bound=new AtomicBoolean();
        IBinder.DeathRecipient death=dead::countDown;
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){
                try {
                    require(context.getPackageName().equals(name.getPackageName())
                            &&TorProcessService.class.getName().equals(name.getClassName()),"EXACT_PRIVATE_SERVICE");
                    exact.set(binder);
                    try{binder.linkToDeath(death,0);}catch(RemoteException alreadyDead){if(!binder.isBinderAlive())dead.countDown();else throw alreadyDead;}
                    // Deliberately no Messenger construction, START, SAMPLE or foreground renewal.
                } catch(Throwable error){failure.set(error);} finally {connected.countDown();}
            }
            @Override public void onServiceDisconnected(ComponentName name){IBinder binder=exact.get();if(binder!=null&&!binder.isBinderAlive())dead.countDown();}
            @Override public void onBindingDied(ComponentName name){/* Binding failure is not process-death proof. */}
            public void onNullBinding(ComponentName name){failure.set(new AssertionError("OFFLINE_BINDING_REQUIRED"));connected.countDown();}
        };
        Intent service=new Intent(context,TorProcessService.class);
        try {
            instrumentation.runOnMainSync(()->{
                try {
                    require(context.startService(service)!=null,"OFFLINE_SERVICE_START");
                    bound.set(context.bindService(service,connection,0));
                    require(bound.get(),"NO_AUTO_CREATE_BIND");
                } catch(Throwable error){failure.set(error);connected.countDown();}
            });
            require(connected.await(10,TimeUnit.SECONDS),"PRIVATE_BINDER_TIMEOUT");
            require(failure.get()==null&&exact.get()!=null,"PRIVATE_BINDER_REQUIRED");
            if(explicitStop)instrumentation.runOnMainSync(()->{
                context.stopService(service);
                if(bound.compareAndSet(true,false))context.unbindService(connection);
            });
            require(dead.await(10,TimeUnit.SECONDS),"EXACT_PRIVATE_BINDER_DEATH_REQUIRED");
            require(!exact.get().isBinderAlive(),"NATIVE_INCARNATION_TERMINATION_CONFIRMED");
        } finally {
            instrumentation.runOnMainSync(()->{
                context.stopService(service);
                if(bound.compareAndSet(true,false))context.unbindService(connection);
            });
            IBinder binder=exact.get();if(binder!=null)binder.unlinkToDeath(death,0);
        }
    }
    private static void require(boolean condition,String code){TorTalkRuntimeInstrumentation.require(condition,code);}
}
