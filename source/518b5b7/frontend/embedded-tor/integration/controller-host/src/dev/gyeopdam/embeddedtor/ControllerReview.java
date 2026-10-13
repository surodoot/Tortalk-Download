package dev.gyeopdam.embeddedtor;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real application controller/protocol/gate, explicit Android IPC and service lifecycle doubles. */
public final class ControllerReview {
    private static Context context;
    private static EmbeddedTorController controller;
    private static Path directory;
    public static void main(String[] args)throws Exception {
        Looper.getMainLooper();directory=Files.createTempDirectory("tor-process-controller-host-");
        context=new Context(directory.toFile());controller=EmbeddedTorController.get(new Context(context));
        try {
            switch(args[0]) {
                case "offline-opt-in" -> offline();
                case "explicit-start" -> {running();eq(1,context.starts,"one explicit start");eq(1,context.binds,"one non-auto binding");}
                case "repeated-start" -> {running();for(int i=0;i<10;i++)controller.start();eq(1,context.starts,"repeated clicks do not spawn");}
                case "foreground-owners" -> foregroundOwners();
                case "normal-stop" -> normalStop();
                case "stop-ack-not-death" -> stopAck();
                case "exact-binder-death" -> exactDeath();
                case "explicit-restart" -> explicitRestart();
                case "unexpected-death" -> unexpectedDeath();
                case "late-old-reply" -> oldReply();
                case "duplicate-binding" -> duplicateBinding();
                case "stop-before-binding" -> stopBeforeBinding();
                case "null-binding" -> {start();context.connection.onNullBinding(new ComponentName());unconfirmed();}
                case "bind-refused" -> {context.bindResult=false;start();unconfirmed();eq(0,context.unbinds,"failed bind not unbound");}
                case "start-refused" -> {context.throwOnStart=true;start();unconfirmed();eq(0,context.binds,"refused start never binds");}
                case "bind-timeout" -> {start();advance(10_001);unconfirmed();}
                case "command-timeout" -> commandTimeout();
                case "ready-expiry" -> readyExpiry();
                case "wrong-uid" -> wrongUid();
                case "endpoint-identity" -> endpointIdentity();
                case "main-pid-rejected" -> mainPidRejected();
                case "changed-pid-rejected" -> changedPidRejected();
                case "pid-pinned-before-ready" -> pidPinnedBeforeReady();
                case "invalid-pid-schema" -> invalidPidSchema();
                case "remote-path-schema" -> remotePathSchema();
                case "invalid-schema" -> invalidSchema();
                case "stale-sequence" -> staleSequence();
                case "stale-timestamp" -> staleTimestamp();
                case "future-timestamp" -> futureTimestamp();
                case "wrong-epoch" -> wrongEpoch();
                case "stop-ack-clock" -> stopAckClock();
                case "control-error" -> controlError();
                case "repeated-stop" -> repeatedStop();
                case "dead-before-binding" -> {start();context.remote.die();context.connect();Handler.drain();check(controller.canStart()&&controller.lease()==null,"already dead exact binder permits manual retry without START");check(context.requests.isEmpty(),"no command to dead incarnation");}
                case "foreign-component" -> {start();context.connection.onServiceConnected(new ComponentName("foreign.app","WrongService"),context.remote);unconfirmed();}
                case "binding-died" -> bindingDied();
                case "callback-isolation" -> callbackIsolation();
                case "probe-cancel" -> probeCancel();
                case "main-thread-only" -> mainThread();
                default -> throw new AssertionError("Unknown scenario");
            }
            System.out.println("PASS "+args[0]);
        } finally {Files.deleteIfExists(directory);}
    }
    private static void offline(){check(controller==EmbeddedTorController.get(context),"application singleton");check(!controller.canStart(),"background denies opt-in");controller.start();eq(0,context.starts,"no background service");controller.attachForeground();eq(0,context.starts,"foreground alone never starts");check(controller.canStart(),"explicit button enabled");eq(EmbeddedTorController.NativeStopState.NOT_STARTED,controller.nativeStopState(),"no false stop proof");}
    private static void start(){controller.attachForeground();controller.start();Handler.drain();}
    private static Message connected() {start();context.connect();Handler.drain();return pop(TorProcessProtocol.START);}
    private static void running()throws Exception {Message request=connected();readyReply(request);check(controller.lease()!=null,"validated current sample grants lease");}
    private static Message pop(int command){Handler.drain();check(!context.requests.isEmpty(),"expected IPC command");Message value=context.requests.remove(0);eq(command,value.what,"fixed command");check(TorProcessProtocol.readRequest(value)!=null,"strict request schema");return value;}
    private static void readyReply(Message request)throws Exception {Bundle d=request.getData();request.replyTo.send(TorProcessProtocol.sample(d.getLong("epoch"),d.getLong("seq"),SystemClock.elapsedRealtime(),100,1,1,android.os.Process.TOR_PID));Handler.drain();}
    private static void stopReply(Message request)throws Exception {Bundle d=request.getData();request.replyTo.send(TorProcessProtocol.stopped(d.getLong("epoch"),d.getLong("seq"),SystemClock.elapsedRealtime()));Handler.drain();}
    private static void advance(long amount){SystemClock.advance(amount);Handler.drain();}
    private static AtomicInteger counter(){AtomicInteger count=new AtomicInteger();controller.addCancellation(count::incrementAndGet);return count;}
    private static void revoked(TorReadinessGate.ReadyLease lease,AtomicInteger canceled){check(!controller.current(lease)&&controller.lease()==null,"API lease synchronously revoked");eq(1,canceled.get(),"all API requests canceled before acknowledgment");}
    private static void normalStop()throws Exception {running();TorReadinessGate.ReadyLease lease=controller.lease();AtomicInteger canceled=counter();controller.stop();revoked(lease,canceled);eq(1,context.unbinds,"unbind without assuming death");check(context.stops>=1,"started service receives stop request");check(!controller.canStart(),"restart denied while process unconfirmed");eq(EmbeddedTorController.NativeStopState.STOP_REQUESTED,controller.nativeStopState(),"requested is not stopped");pop(TorProcessProtocol.STOP);}
    private static void stopAck()throws Exception {running();controller.stop();Message stop=pop(TorProcessProtocol.STOP);stopReply(stop);eq(EmbeddedTorController.NativeStopState.OFFLINE_CONFIRMED,controller.nativeStopState(),"only actual ACK grants offline status");check(!controller.canStart()&&controller.isPausePending(),"ACK does not prove native exit");advance(4_000);eq(EmbeddedTorController.NativeStopState.OFFLINE_CONFIRMED,controller.nativeStopState(),"timer cannot invent termination");}
    private static void exactDeath()throws Exception {running();controller.stop();pop(TorProcessProtocol.STOP);advance(3_001);eq(EmbeddedTorController.NativeStopState.TERMINATION_UNCONFIRMED,controller.nativeStopState(),"deadline is not death");context.connection.onServiceDisconnected(new ComponentName());check(!controller.canStart(),"disconnect alone is not death");context.remote.die();Handler.drain();eq(EmbeddedTorController.NativeStopState.TERMINATED_CONFIRMED,controller.nativeStopState(),"exact binder death confirms incarnation");check(controller.canStart(),"manual retry allowed after death");}
    private static void explicitRestart()throws Exception {running();controller.stop();pop(TorProcessProtocol.STOP);context.remote.die();Handler.drain();eq(1,context.starts,"death does not auto restart");controller.start();context.connect();Handler.drain();Message next=pop(TorProcessProtocol.START);readyReply(next);eq(2,context.starts,"new explicit opt-in gets fresh process");}
    private static void unexpectedDeath()throws Exception {running();TorReadinessGate.ReadyLease old=controller.lease();AtomicInteger canceled=counter();context.remote.die();Handler.drain();revoked(old,canceled);eq(1,context.starts,"unexpected death never auto restarts");check(controller.canStart(),"manual fresh retry remains possible");}
    private static void oldReply()throws Exception {Message old=connected();readyReply(old);controller.stop();pop(TorProcessProtocol.STOP);context.remote.die();Handler.drain();controller.start();context.connect();Handler.drain();Message next=pop(TorProcessProtocol.START);readyReply(next);TorReadinessGate.ReadyLease lease=controller.lease();Bundle d=old.getData();old.replyTo.send(TorProcessProtocol.sample(d.getLong("epoch"),d.getLong("seq"),SystemClock.elapsedRealtime(),100,1,1,android.os.Process.TOR_PID+1));Handler.drain();check(controller.current(lease),"old incarnation cannot replace current listener");}
    private static void duplicateBinding()throws Exception {running();context.connect();Handler.drain();check(context.requests.isEmpty(),"duplicate binding never sends another START");}
    private static void stopBeforeBinding()throws Exception {start();controller.stop();advance(3_001);check(!controller.canStart(),"without binder, timeout cannot prove process exit");context.connect();Handler.drain();Message stop=pop(TorProcessProtocol.STOP);check(context.requests.isEmpty(),"late binding never enables network");stopReply(stop);check(!controller.canStart(),"late offline ACK is not exit");context.remote.die();Handler.drain();check(controller.canStart(),"late exact binder death permits manual retry");}
    private static void unconfirmed(){advance(3_001);check(!controller.canStart()&&controller.lease()==null,"unobserved process never authorizes retry or network");eq(EmbeddedTorController.NativeStopState.TERMINATION_UNCONFIRMED,controller.nativeStopState(),"no timer-based false proof");}
    private static void commandTimeout(){connected();advance(2_501);check(controller.lease()==null,"hung command grants no lease");check(!controller.canStart(),"hung process not reused");pop(TorProcessProtocol.STOP);}
    private static void readyExpiry()throws Exception {running();TorReadinessGate.ReadyLease lease=controller.lease();AtomicInteger canceled=counter();advance(2_001);revoked(lease,canceled);pop(TorProcessProtocol.STOP);}
    private static void wrongUid()throws Exception {Message req=connected();Bundle d=req.getData();Message reply=TorProcessProtocol.sample(d.getLong("epoch"),d.getLong("seq"),SystemClock.elapsedRealtime(),100,1,1,android.os.Process.TOR_PID);reply.sendingUid=20002;req.replyTo.getBinder().deliver(reply);Handler.drain();check(controller.lease()==null,"foreign UID cannot claim readiness");readyReply(req);check(controller.lease()!=null,"legitimate response still accepted");}
    private static void invalidSchema()throws Exception {Message req=connected();Bundle d=req.getData();Message reply=TorProcessProtocol.sample(d.getLong("epoch"),d.getLong("seq"),SystemClock.elapsedRealtime(),100,1,1,android.os.Process.TOR_PID);reply.getData().putString("progress","100");req.replyTo.send(reply);Handler.drain();check(controller.lease()==null,"coerced type rejected");reply=TorProcessProtocol.sample(d.getLong("epoch"),d.getLong("seq"),SystemClock.elapsedRealtime(),100,1,1,android.os.Process.TOR_PID);reply.getData().putInt("extra",1);req.replyTo.send(reply);Handler.drain();check(controller.lease()==null,"extra fields rejected");readyReply(req);}
    private static void staleSequence()throws Exception {Message req=connected();Bundle d=req.getData();req.replyTo.send(TorProcessProtocol.sample(d.getLong("epoch"),d.getLong("seq")+1,SystemClock.elapsedRealtime(),100,1,1,android.os.Process.TOR_PID));Handler.drain();check(controller.lease()==null,"unsolicited future sequence ignored");readyReply(req);}
    private static void staleTimestamp()throws Exception {Message req=connected();Bundle d=req.getData();req.replyTo.send(TorProcessProtocol.sample(d.getLong("epoch"),d.getLong("seq"),d.getLong("sentAt")-1,100,1,1,android.os.Process.TOR_PID));Handler.drain();check(controller.lease()==null&&!controller.canStart(),"old sample fails closed and stops incarnation");pop(TorProcessProtocol.STOP);}
    private static void futureTimestamp()throws Exception {Message req=connected();Bundle d=req.getData();req.replyTo.send(TorProcessProtocol.sample(d.getLong("epoch"),d.getLong("seq"),SystemClock.elapsedRealtime()+1,100,1,1,android.os.Process.TOR_PID));Handler.drain();check(controller.lease()==null&&!controller.canStart(),"future sample fails closed");pop(TorProcessProtocol.STOP);}
    private static void wrongEpoch()throws Exception {Message req=connected();Bundle d=req.getData();req.replyTo.send(TorProcessProtocol.sample(d.getLong("epoch")+1,d.getLong("seq"),SystemClock.elapsedRealtime(),100,1,1,android.os.Process.TOR_PID));Handler.drain();check(controller.lease()==null,"wrong epoch ignored");readyReply(req);}
    private static void stopAckClock()throws Exception {running();controller.stop();Message stop=pop(TorProcessProtocol.STOP);Bundle d=stop.getData();stop.replyTo.send(TorProcessProtocol.stopped(d.getLong("epoch"),d.getLong("seq"),d.getLong("sentAt")-1));Handler.drain();eq(EmbeddedTorController.NativeStopState.STOP_REQUESTED,controller.nativeStopState(),"stale ACK cannot claim offline");stop.replyTo.send(TorProcessProtocol.stopped(d.getLong("epoch"),d.getLong("seq"),SystemClock.elapsedRealtime()+1));Handler.drain();eq(EmbeddedTorController.NativeStopState.STOP_REQUESTED,controller.nativeStopState(),"future ACK cannot claim offline");stopReply(stop);}
    private static void controlError()throws Exception {Message req=connected();Bundle d=req.getData();req.replyTo.send(TorProcessProtocol.error(d.getLong("epoch"),d.getLong("seq"),TorProcessProtocol.ERROR_CONTROL));Handler.drain();check(!controller.canStart()&&controller.lease()==null,"control error cannot re-enable or prove exit");pop(TorProcessProtocol.STOP);}
    private static void repeatedStop()throws Exception {running();AtomicInteger canceled=counter();controller.stop();for(int i=0;i<10;i++)controller.stop();eq(1,canceled.get(),"repeated stop cancels once");pop(TorProcessProtocol.STOP);check(context.requests.isEmpty(),"one terminal STOP command");eq(1,context.unbinds,"one binding release");}
    private static void bindingDied()throws Exception {running();context.connection.onBindingDied(new ComponentName());check(!controller.canStart()&&controller.lease()==null,"binding death only requests stop");context.remote.die();Handler.drain();check(controller.canStart(),"exact process binder death completes stop");}
    private static void foregroundOwners()throws Exception {controller.attachForeground();running();controller.detachForeground();check(controller.lease()!=null,"one remaining foreground owner keeps session");controller.detachForeground();check(controller.lease()==null,"last foreground owner revokes");context.remote.die();Handler.drain();check(!controller.canStart(),"background retry forbidden after death");controller.attachForeground();eq(1,context.starts,"foreground return does not auto start");check(controller.canStart(),"new explicit opt-in available");}
    private static void callbackIsolation()throws Exception {AtomicInteger changed=new AtomicInteger();controller.addListener(()->{throw new IllegalStateException("Synthetic UI failure");});controller.addListener(changed::incrementAndGet);running();check(changed.get()>0,"later listeners run");controller.addCancellation(()->{throw new IllegalStateException("Synthetic callback failure");});AtomicInteger canceled=counter();controller.stop();eq(1,canceled.get(),"later cancellation still runs");check(context.stops>=1&&context.unbinds==1,"faulty hook cannot prevent stop/unbind");context.remote.die();Handler.drain();check(controller.canStart(),"faulty hook cannot prevent death completion");}
    private static void probeCancel()throws Exception {controller.requestProbe();check(FixedOnionProbe.calls.isEmpty(),"no pre-ready probe");running();controller.requestProbe();FixedOnionProbe probe=FixedOnionProbe.calls.get(0);controller.cancelProbe();eq(1,probe.cancels,"developer exit cancels diagnostic only");check(controller.lease()!=null,"diagnostic cancel preserves main connection");controller.requestProbe();controller.stop();check(!probe.ready.getAsBoolean(),"stop revokes probe lease");}
    private static void mainThread()throws Exception {AtomicReference<Throwable> failure=new AtomicReference<>();Thread thread=new Thread(()->{try{controller.start();}catch(Throwable value){failure.set(value);}});thread.start();thread.join();check(failure.get() instanceof IllegalStateException,"main-thread contract");eq(0,context.starts,"off-main call cannot start service");}
    private static Message sampleReply(Message request, int progress, int socksReady, int pid) {
        Bundle data=request.getData();
        return TorProcessProtocol.sample(data.getLong("epoch"), data.getLong("seq"), SystemClock.elapsedRealtime(),
                progress, socksReady, 1, pid);
    }
    private static void endpointIdentity() throws Exception {
        running(); TorSocketEndpoint endpoint=controller.lease().endpoint;
        eq(TorSocketPaths.PATH, endpoint.path, "endpoint path comes from app-owned filesystem policy");
        eq(android.os.Process.UID, endpoint.uid, "endpoint binds expected app UID");
        eq(android.os.Process.TOR_PID, endpoint.pid, "endpoint binds current private process PID");
        controller.requestProbe(); eq(endpoint, FixedOnionProbe.calls.get(0).endpoint, "probe inherits exact endpoint lease");
    }
    private static void mainPidRejected() throws Exception {
        Message request=connected(); request.replyTo.send(sampleReply(request,100,1,android.os.Process.MAIN_PID)); Handler.drain();
        check(controller.lease()==null && !controller.canStart(), "main PID cannot grant Tor authority");
        pop(TorProcessProtocol.STOP);
    }
    private static void changedPidRejected() throws Exception {
        running(); TorReadinessGate.ReadyLease lease=controller.lease(); advance(500); Message request=pop(TorProcessProtocol.SAMPLE);
        request.replyTo.send(sampleReply(request,100,1,android.os.Process.TOR_PID+1)); Handler.drain();
        check(!controller.current(lease)&&controller.lease()==null, "same Binder cannot replace pinned PID");
        eq(TorReadinessGate.Phase.FAILED, controller.phase(), "PID change is terminal for current epoch");
        advance(500); pop(TorProcessProtocol.STOP); check(!controller.canStart(), "PID change is not process death proof");
    }
    private static void pidPinnedBeforeReady() throws Exception {
        Message first=connected(); first.replyTo.send(sampleReply(first,0,0,android.os.Process.TOR_PID)); Handler.drain();
        check(controller.lease()==null, "offline initialization grants no endpoint"); advance(500); Message request=pop(TorProcessProtocol.SAMPLE);
        request.replyTo.send(sampleReply(request,100,1,android.os.Process.TOR_PID+1)); Handler.drain();
        check(controller.lease()==null, "PID change during bootstrap cannot establish readiness");
        eq(TorReadinessGate.Phase.FAILED, controller.phase(), "first validated sample pins PID before readiness");
    }
    private static void invalidPidSchema() throws Exception {
        Message request=connected();
        for (int pid:new int[]{0,-1}) {
            Message invalid=sampleReply(request,100,1,pid); check(!TorProcessProtocol.validReply(invalid), "nonpositive PID violates schema");
            request.replyTo.send(invalid); Handler.drain(); check(controller.lease()==null, "invalid PID cannot create endpoint");
        }
        Message wrongType=sampleReply(request,100,1,android.os.Process.TOR_PID); wrongType.getData().putLong("pid",android.os.Process.TOR_PID);
        request.replyTo.send(wrongType); Handler.drain(); check(controller.lease()==null,"coerced PID type rejected");
        readyReply(request); check(controller.lease()!=null,"valid response remains acceptable after rejected schema");
    }
    private static void remotePathSchema() throws Exception {
        Message request=connected(); Message injected=sampleReply(request,100,1,android.os.Process.TOR_PID);
        injected.getData().putString("path","/tmp/foreign"); request.replyTo.send(injected); Handler.drain();
        check(controller.lease()==null,"remote filesystem path cannot enter endpoint authority");
        injected=sampleReply(request,100,1,android.os.Process.TOR_PID); injected.getData().putInt("port",19050);
        request.replyTo.send(injected); Handler.drain(); check(controller.lease()==null,"legacy TCP port cannot enter endpoint authority");
        readyReply(request); eq(TorSocketPaths.PATH,controller.lease().endpoint.path,"accepted sample uses hardwired path only");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void eq(Object expected,Object actual,String message){if(!expected.equals(actual))throw new AssertionError(message+" expected="+expected+" actual="+actual);}
}
