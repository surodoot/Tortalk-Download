package dev.gyeopdam.embeddedtor;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Application-owned Tor gate. Native Tor exists only in the private, disposable :tor process. */
final class EmbeddedTorController {
    enum NativeStopState { NOT_STARTED, START_REQUESTED, RUNNING, STOP_REQUESTED,
        OFFLINE_CONFIRMED, TERMINATED_CONFIRMED, TERMINATION_UNCONFIRMED }
    private static final long BIND_DEADLINE_MS=10_000, COMMAND_DEADLINE_MS=2_500, STOP_CONFIRM_MS=3_000;
    // get() normalizes to the application context; no Activity or View is retained here.
    @SuppressLint("StaticFieldLeak")
    private static EmbeddedTorController singleton;
    static synchronized EmbeddedTorController get(Context context) {
        if(singleton==null)singleton=new EmbeddedTorController(context.getApplicationContext());return singleton;
    }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final TorReadinessGate gate;
    private final String socksPath;
    private final TorLifecyclePolicy policy=new TorLifecyclePolicy();
    private final Set<Runnable> listeners=new LinkedHashSet<>(),cancellations=new LinkedHashSet<>();
    private final OnionProbeSession probe;
    private volatile boolean requested;
    private NativeStopState nativeState=NativeStopState.NOT_STARTED;
    private volatile Attempt active;
    private long nextEpoch,readinessEpoch;

    private EmbeddedTorController(Context context) {
        this.context=context;
        socksPath=TorSocketPaths.socksPath(context);
        gate=new TorReadinessGate(socksPath,Process.myUid());
        probe=new OnionProbeSession(gate,SystemClock::elapsedRealtime,FixedOnionProbe::new,
                action->main.post(action),this::changed);
    }
    private final class Attempt implements ServiceConnection {
        final long epoch=++nextEpoch;
        final long gateEpoch=readinessEpoch;
        final long createdAt=SystemClock.elapsedRealtime();
        final Messenger owner=new Messenger(new Handler(Looper.getMainLooper(),message->{receive(this,message);return true;}));
        volatile boolean deathObserved;
        final IBinder.DeathRecipient death=()->{
            deathObserved=true;gate.fail(gateEpoch);main.post(()->died(this));
        };
        volatile IBinder binder;
        Messenger remote;
        boolean bound,started,stopping,dead,linked;
        long sequence,pendingSequence,pendingAt,stopSequence,stopSentAt;
        @Override public void onServiceConnected(ComponentName name,IBinder endpoint) {
            if(active!=this){releaseBinding(this);return;}
            if(endpoint==null||name==null||!context.getPackageName().equals(name.getPackageName())
                    ||!TorProcessService.class.getName().equals(name.getClassName())){beginStop(true);return;}
            if(binder!=null&&binder!=endpoint){beginStop(true);return;}
            if(linked&&binder==endpoint)return;
            binder=endpoint;remote=new Messenger(endpoint);
            try{endpoint.linkToDeath(death,0);linked=true;}
            catch(RemoteException unavailable){if(!endpoint.isBinderAlive())died(this);else beginStop(true);return;}
            if(deathObserved||!endpoint.isBinderAlive()){died(this);return;}
            if(stopping||!requested){sendStop(this);stopServiceAndUnbind(this);return;}
            send(this,TorProcessProtocol.START);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if(active!=this)return;
            if(binder!=null&&!binder.isBinderAlive())died(this);else beginStop(true);
        }
        @Override public void onBindingDied(ComponentName name){if(active==this)beginStop(true);}
        public void onNullBinding(ComponentName name){if(active==this)beginStop(true);}
    }
    void addListener(Runnable listener){requireMainThread();listeners.add(listener);}
    void removeListener(Runnable listener){requireMainThread();listeners.remove(listener);}
    void attachForeground(){requireMainThread();policy.foreground();changed();}
    void detachForeground(){requireMainThread();if(policy.background())stop();else changed();}
    void addCancellation(Runnable cancellation){requireMainThread();cancellations.add(cancellation);}
    void removeCancellation(Runnable cancellation){requireMainThread();cancellations.remove(cancellation);}
    boolean canStart(){return policy.canStart()&&(active==null||active.dead);}
    boolean isPausePending(){return active!=null&&active.stopping&&!active.dead;}
    boolean needsRestart(){return nativeState==NativeStopState.TERMINATION_UNCONFIRMED&&active!=null&&active.binder==null;}
    NativeStopState nativeStopState(){return nativeState;}
    TorReadinessGate.Phase phase(){return gate.phase(SystemClock.elapsedRealtime());}
    int progress(){return gate.progress();}
    OnionProbeSession.Result probeResult(){probe.refresh();return probe.result();}
    boolean canProbe(){probe.refresh();return probe.canStart();}
    void requestProbe(){requireMainThread();probe.start();}
    void cancelProbe(){requireMainThread();probe.cancel();changed();}
    TorReadinessGate.ReadyLease lease(){
        TorReadinessGate.ReadyLease value=requested?gate.readyLease(SystemClock.elapsedRealtime()):null;
        return current(value)?value:null;
    }
    boolean current(TorReadinessGate.ReadyLease lease){
        Attempt attempt=active;IBinder endpoint=attempt==null?null:attempt.binder;
        // Transport workers must observe death without waiting for the UI callback queue.
        return requested&&attempt!=null&&!attempt.deathObserved&&endpoint!=null
                &&endpoint.isBinderAlive()&&gate.isCurrent(lease,SystemClock.elapsedRealtime());
    }

    void start() {
        requireMainThread();if(!canStart()||!policy.start())return;
        requested=true;readinessEpoch=gate.begin(SystemClock.elapsedRealtime());
        nativeState=NativeStopState.START_REQUESTED;
        Attempt attempt=new Attempt();active=attempt;
        try {
            // Only an explicit foreground opt-in starts this non-sticky service. Never AUTO_CREATE-bind.
            ComponentName component=context.startService(serviceIntent());
            if(component==null){beginStop(true);return;}
            attempt.started=true;
            attempt.bound=context.bindService(serviceIntent(),attempt,0);
            if(!attempt.bound){beginStop(true);return;}
        } catch(RuntimeException unavailable){beginStop(true);return;}
        main.removeCallbacks(poll);main.post(poll);changed();
    }
    private Intent serviceIntent(){return new Intent(context,TorProcessService.class);}
    private void send(Attempt attempt,int command) {
        if(active!=attempt||attempt.remote==null||attempt.dead)return;
        long sequence=++attempt.sequence;
        long now=SystemClock.elapsedRealtime();
        if(command==TorProcessProtocol.STOP){attempt.stopSequence=sequence;attempt.stopSentAt=now;}
        else {attempt.pendingSequence=sequence;attempt.pendingAt=now;}
        try{attempt.remote.send(TorProcessProtocol.request(command,attempt.epoch,sequence,now,attempt.owner));}
        catch(RemoteException|RuntimeException unavailable){
            if(attempt.binder!=null&&!attempt.binder.isBinderAlive())died(attempt);
            else if(command!=TorProcessProtocol.STOP)beginStop(true);
        }
    }
    private void sendStop(Attempt attempt){if(attempt.stopSequence==0)send(attempt,TorProcessProtocol.STOP);}
    private void receive(Attempt attempt,Message message) {
        requireMainThread();
        if(active!=attempt||attempt.dead||attempt.deathObserved||message.sendingUid!=Process.myUid()||!TorProcessProtocol.validReply(message))return;
        Bundle data=message.getData();
        if(data.getLong("epoch")!=attempt.epoch)return;
        long sequence=data.getLong("seq");
        if(message.what==TorProcessProtocol.STOP_ACK) {
            if(!attempt.stopping||sequence!=attempt.stopSequence)return;
            long acknowledgedAt=data.getLong("sampledAt");
            if(acknowledgedAt<attempt.stopSentAt||acknowledgedAt>SystemClock.elapsedRealtime())return;
            nativeState=NativeStopState.OFFLINE_CONFIRMED;changed();return;
        }
        if(message.what==TorProcessProtocol.ERROR) {
            if(sequence!=attempt.pendingSequence&&sequence!=attempt.stopSequence)return;
            beginStop(true);return;
        }
        if(!requested||attempt.stopping||sequence!=attempt.pendingSequence)return;
        attempt.pendingSequence=0;
        long now=SystemClock.elapsedRealtime(),sampledAt=data.getLong("sampledAt");
        if(sampledAt<attempt.pendingAt||sampledAt>now||now-sampledAt>2_000){beginStop(true);return;}
        int progress=data.getInt("progress"),socksReady=data.getInt("socksReady"),circuit=data.getInt("circuit"),pid=data.getInt("pid");
        if(pid==Process.myPid()){beginStop(true);return;}
        gate.sample(readinessEpoch,sampledAt,"PROGRESS="+progress,circuit==1?"1":"0",
                socksReady==1?new TorSocketEndpoint(socksPath,Process.myUid(),pid).controlListener():"",pid);
        nativeState=NativeStopState.RUNNING;changed();
    }
    private final Runnable poll=new Runnable(){@Override public void run(){
        Attempt attempt=active;if(!requested||attempt==null||attempt.stopping||attempt.dead)return;
        long now=SystemClock.elapsedRealtime();
        if(phase()==TorReadinessGate.Phase.FAILED
                ||attempt.binder==null&&now-attempt.createdAt>BIND_DEADLINE_MS
                ||attempt.pendingSequence!=0&&now-attempt.pendingAt>COMMAND_DEADLINE_MS){beginStop(true);return;}
        if(attempt.remote!=null&&attempt.pendingSequence==0)send(attempt,TorProcessProtocol.SAMPLE);
        changed();if(requested&&active==attempt&&!attempt.stopping)main.postDelayed(this,500);
    }};
    void stop(){requireMainThread();beginStop(false);}
    private void beginStop(boolean failed) {
        requireMainThread();
        Attempt attempt=active;
        if(requested){requested=false;policy.stop();if(failed)gate.fail(readinessEpoch);else gate.stop();probe.cancel();cancelRequests();}
        else {policy.stop();if(attempt==null)gate.stop();}
        main.removeCallbacks(poll);
        if(attempt==null||attempt.dead){changed();return;}
        if(!attempt.stopping){
            attempt.stopping=true;nativeState=NativeStopState.STOP_REQUESTED;
            sendStop(attempt);stopServiceAndUnbind(attempt);
            main.postDelayed(()->{
                if(active==attempt&&!attempt.dead&&nativeState!=NativeStopState.OFFLINE_CONFIRMED){
                    nativeState=NativeStopState.TERMINATION_UNCONFIRMED;changed();
                }
            },STOP_CONFIRM_MS);
        }
        changed();
    }
    private void stopServiceAndUnbind(Attempt attempt) {
        if(active!=attempt)return;
        try{context.stopService(serviceIntent());}catch(RuntimeException ignored){/* Binder death remains the proof boundary. */}
        releaseBinding(attempt);
    }
    private void releaseBinding(Attempt attempt) {
        if(!attempt.bound)return;attempt.bound=false;
        try{context.unbindService(attempt);}catch(RuntimeException ignored){/* Never infer process exit. */}
    }
    private void died(Attempt attempt) {
        requireMainThread();if(active!=attempt||attempt.dead)return;
        // Only a retained exact Binder's death (or isBinderAlive=false) confirms this incarnation ended.
        if(attempt.binder==null)return;
        attempt.deathObserved=true;attempt.dead=true;
        if(requested){requested=false;policy.stop();gate.fail(readinessEpoch);probe.cancel();cancelRequests();}
        main.removeCallbacks(poll);releaseBinding(attempt);
        try{context.stopService(serviceIntent());}catch(RuntimeException ignored){/* This Binder is already dead. */}
        if(attempt.linked){attempt.binder.unlinkToDeath(attempt.death,0);attempt.linked=false;}
        attempt.remote=null;nativeState=NativeStopState.TERMINATED_CONFIRMED;changed();
    }
    private void cancelRequests(){for(Runnable action:new ArrayList<>(cancellations)){try{action.run();}catch(RuntimeException ignored){}}}
    private void changed(){for(Runnable listener:new ArrayList<>(listeners)){try{listener.run();}catch(RuntimeException ignored){}}}
    private static void requireMainThread(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("MAIN_THREAD_REQUIRED");}
}
