package dev.gyeopdam.embeddedtor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import java.nio.file.Files;
import java.util.List;
import net.freehaven.tor.control.ConfigEntry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.freehaven.tor.control.TorControlConnection;
import org.torproject.jni.TorService;

/** Runs the real wrapper with documented framework/vendor doubles, one JVM per scenario. */
public final class TorProcessReview {
    private final TorProcessService service = new TorProcessService();
    private final BlockingQueue<Message> replies = new LinkedBlockingQueue<>();
    private final Messenger owner = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        replies.add(message); return true;
    }));
    private Messenger remote;
    private long sequence;

    public static void main(String[] args) throws Exception {
        TorService.configFile = Files.createTempFile("tor-process-host-", ".torrc").toFile();
        TorService.configFile.deleteOnExit();
        new TorProcessReview().run(args[0]);
        System.out.println("PASS " + args[0] + " (host doubles; no Android/Tor/process kill)");
    }

    private void run(String scenario) throws Exception {
        switch (scenario) {
            case "reject-main-process":
                Process.pid = Process.MAIN_PID; ActivityManager.processName = Context.PACKAGE;
                identityRejected(); return;
            case "reject-package-uid":
                Context.applicationUid = Process.UID + 1; identityRejected(); return;
            case "reject-process-uid":
                ActivityManager.recordUid = Process.UID + 1; identityRejected(); return;
            case "reject-unavailable-identity":
                ActivityManager.available = false; identityRejected(); return;
            case "hung-getinfo":
                TorService.control.mode = TorControlConnection.Mode.HANG_GETINFO;
                create(); send(TorProcessProtocol.START); entered(TorService.control.getInfoEntered);
                killAt(3000); check(replies.isEmpty(), "Hung sample must not reply"); return;
            case "reject-socket-path-prepare":
                TorSocketPaths.rejectPrepare = true; preparationRejected(); return;
            case "reject-control-path-prepare":
                TorSocketPaths.rejectControlPrepare = true; preparationRejected(); return;
            case "hung-socket-getconf":
                TorService.control.mode = TorControlConnection.Mode.HANG_GETCONF;
                create(); send(TorProcessProtocol.START); entered(TorService.control.getConfEntered);
                killAt(3000); check(replies.isEmpty(), "Hung GETCONF advertised readiness");
                check(TorService.control.enables.get() == 0, "Hung GETCONF enabled network");
                TorService.control.getConfRelease.countDown(); remote.idle(); Thread.sleep(50);
                check(TorService.control.enables.get() == 0, "Late GETCONF revived a timed-out incarnation"); return;
            case "wrong-socket-getconf":
                TorService.control.configurationOverride = List.of(new ConfigEntry("SocksPort", "127.0.0.1:19050"));
                configurationRejected(); return;
            case "foreign-socket-getconf":
                TorService.control.configurationOverride = List.of(new ConfigEntry("SocksPort", "unix:/tmp/foreign OnionTrafficOnly"));
                configurationRejected(); return;
            case "extra-socket-getconf":
                TorService.control.configurationOverride = List.of(
                        new ConfigEntry("SocksPort", "unix:" + TorSocketPaths.PATH + " OnionTrafficOnly"),
                        new ConfigEntry("SocksPort", "127.0.0.1:19050"));
                configurationRejected(); return;
            case "empty-socket-getconf":
                TorService.control.configurationOverride = List.of(); configurationRejected(); return;
            case "wrong-key-getconf":
                TorService.control.configurationOverride = List.of(new ConfigEntry("ControlPort", "unix:" + TorSocketPaths.PATH + " OnionTrafficOnly"));
                configurationRejected(); return;
            case "reject-directory-before-enable":
                TorSocketPaths.rejectDirectoryVerify = true; configurationRejected(); return;
            case "reject-socket-before-ready":
                TorSocketPaths.rejectVerify = true; create(); send(TorProcessProtocol.START); reply(TorProcessProtocol.ERROR);
                check(TorService.control.enables.get() == 1, "Socket verification was not exercised after enable");
                check(!TorService.socketVerified && noReply(TorProcessProtocol.SAMPLE_RESULT), "Unverified socket advertised readiness");
                killAt(3000); return;
            case "hung-socket-configure":
                TorService.control.mode = TorControlConnection.Mode.HANG_SOCKET;
                create(); send(TorProcessProtocol.START); entered(TorService.control.configureEntered);
                killAt(3000); check(replies.isEmpty(), "Hung socket configuration advertised readiness");
                check(TorService.control.enables.get() == 0, "Hung socket configuration enabled network");
                TorService.control.configureRelease.countDown(); remote.idle(); Thread.sleep(50);
                check(TorService.control.enables.get() == 0, "Late socket configuration revived a timed-out incarnation"); return;
            case "failed-socket-configure":
                TorService.control.mode = TorControlConnection.Mode.FAIL_SOCKET;
                create(); send(TorProcessProtocol.START); reply(TorProcessProtocol.ERROR);
                check(TorService.control.enables.get() == 0, "Failed socket configuration enabled network");
                check(noReply(TorProcessProtocol.SAMPLE_RESULT), "Failed socket configuration advertised readiness");
                killAt(3000); return;
            case "socket-configure-order":
                ready();
                check(TorService.control.events.get(0).equals("set:SocksPort=unix:" + TorSocketPaths.PATH + " OnionTrafficOnly"),
                        "First authenticated command must configure hardwired Unix listener");
                check(TorService.control.events.get(1).equals("conf:SocksPort"), "Configured listener was not read back before enable");
                check(TorService.control.events.get(2).equals("verify-directory"), "Private directory was not verified before enable");
                check(TorService.control.events.get(3).equals("set:DisableNetwork=0"), "Network enabled before verified socket configuration");
                check(TorService.control.events.get(7).equals("verify-socket"), "Socket verification did not follow all three readiness queries");
                send(TorProcessProtocol.SAMPLE); reply(TorProcessProtocol.SAMPLE_RESULT);
                check(TorService.control.socketConfigurations.get() == 1 && TorService.control.enables.get() == 1,
                        "Fresh samples reconfigured or reopened listener"); return;
            case "tcp-listener-rejected":
                TorService.control.listenerOverride = "\"127.0.0.1:19050\"";
                create(); send(TorProcessProtocol.START); Message tcp = reply(TorProcessProtocol.SAMPLE_RESULT);
                check(tcp.getData().getInt("socksReady") == 0, "TCP listener accepted as private endpoint"); return;
            case "foreign-unix-listener-rejected":
                TorService.control.listenerOverride = "\"unix:/tmp/foreign\"";
                create(); send(TorProcessProtocol.START); Message foreignPath = reply(TorProcessProtocol.SAMPLE_RESULT);
                check(foreignPath.getData().getInt("socksReady") == 0, "Foreign filesystem listener accepted"); return;
            case "hung-enable":
                TorService.control.mode = TorControlConnection.Mode.HANG_ENABLE;
                create(); send(TorProcessProtocol.START); entered(TorService.control.enableEntered);
                killAt(3000); check(replies.isEmpty(), "Hung enable must not advertise readiness"); return;
            case "hung-disable":
                ready(); TorService.control.mode = TorControlConnection.Mode.HANG_DISABLE;
                send(TorProcessProtocol.STOP); entered(TorService.control.disableEntered);
                killAt(3000); check(noReply(TorProcessProtocol.STOP_ACK), "Hung disable must not acknowledge"); return;
            case "hung-super-destroy":
                ready(); TorService.hangDestroy = true;
                Looper.getMainLooper().execute(service::onDestroy); entered(TorService.destroyEntered);
                check(TorService.createThread == TorService.destroyThread, "Superclass lifecycle changed threads");
                killAt(3000); return;
            case "normal-super-destroy":
                ready(); onMain(service::onDestroy);
                check(TorService.createThread == TorService.destroyThread, "Superclass lifecycle changed threads");
                awaitKill(); return;
            case "owner-death":
                ready(); ((Binder)owner.getBinder()).die();
                eventually(() -> service.stopRequests.get() > 0, "Owner death must request stop");
                killAt(3000); return;
            case "foreground-lease":
                ready(); SystemClock.set(5000);
                eventually(() -> service.stopRequests.get() > 0, "Expired foreground lease must request stop");
                killAt(7000); return;
            case "blocked-outbound":
                ((Messenger.Endpoint)owner.getBinder()).blockSend = new CountDownLatch(1);
                create(); send(TorProcessProtocol.START);
                entered(((Messenger.Endpoint)owner.getBinder()).sendEntered);
                SystemClock.set(5000);
                eventually(() -> service.stopRequests.get() > 0, "Blocked reply must not block lease watchdog");
                killAt(7000); return;
            case "wrong-uid":
                create();
                remote.sendFromUid(request(TorProcessProtocol.START), Process.UID + 1); remote.idle();
                check(TorService.control.enables.get() == 0, "Wrong UID enabled Tor");
                check(service.stopRequests.get() == 0, "Wrong UID caused owner-session mutation");
                killAt(6000); return;
            case "wrong-protocol":
                create(); Message malformed = request(TorProcessProtocol.START);
                malformed.getData().putString("command", "SETCONF DisableNetwork=0");
                remote.send(malformed); remote.idle();
                check(TorService.control.enables.get() == 0, "Extra raw-command key enabled Tor");
                eventually(() -> service.stopRequests.get() > 0, "Malformed request must fail closed");
                killAt(3000); return;
            case "wrong-primitive-type":
                create(); Message badType = request(TorProcessProtocol.START);
                badType.getData().putInt("epoch", 1); remote.send(badType); remote.idle();
                check(TorService.control.enables.get() == 0, "Wrong primitive type enabled Tor");
                killAt(3000); return;
            case "foreign-owner":
                ready(); Messenger foreign = new Messenger(new Handler(Looper.getMainLooper(), message -> true));
                remote.send(TorProcessProtocol.request(TorProcessProtocol.STOP, 1, ++sequence,
                        SystemClock.elapsedRealtime(), foreign)); remote.idle();
                check(service.stopRequests.get() == 0 && TorService.control.disables.get() == 0,
                        "Foreign owner stopped the current session"); return;
            case "unclaimed-startup":
                create(); check(TorService.control.enables.get() == 0, "Unclaimed startup enabled networking");
                killAt(6000); return;
            case "authentication-pending":
                TorService.authenticatedMarker=-1;create();send(TorProcessProtocol.START);
                Message pendingAuth=reply(TorProcessProtocol.SAMPLE_RESULT);
                check(pendingAuth.getData().getInt("socksReady")==0&&TorService.control.enables.get()==0&&TorService.control.socketConfigurations.get()==0,"Published connection before auth must stay offline");
                TorService.authenticatedMarker=0;send(TorProcessProtocol.SAMPLE);
                Message authenticated=reply(TorProcessProtocol.SAMPLE_RESULT);
                check(authenticated.getData().getInt("socksReady")==1&&TorService.control.enables.get()==1,"Auth marker0 must permit fresh strict listener query");return;
            case "nonzero-auth-marker":
                TorService.authenticatedMarker = 19050; create(); send(TorProcessProtocol.START);
                reply(TorProcessProtocol.ERROR);
                check(TorService.control.socketConfigurations.get() == 0 && TorService.control.enables.get() == 0,
                        "Nonzero marker accepted as offline authentication proof");
                check(noReply(TorProcessProtocol.SAMPLE_RESULT), "Positive TCP marker advertised readiness"); killAt(3000); return;
            case "hung-auth-monitor":
                CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
                Thread holder=new Thread(()->{synchronized(TorService.control){held.countDown();try{release.await();}catch(InterruptedException failure){throw new AssertionError(failure);}}});
                holder.setDaemon(true);holder.start();entered(held);create();send(TorProcessProtocol.START);remote.idle();entered(TorService.controlReadEntered);
                killAt(3000);check(TorService.control.enables.get()==0,"Auth monitor hang must not enable Tor");
                release.countDown();remote.idle();Thread.sleep(50);
                check(TorService.control.enables.get()==0,"Late auth completion must not reopen timed-out incarnation");return;
            case "cold-control-start":
                TorService.control = null; create(); send(TorProcessProtocol.START);
                Message waiting = reply(TorProcessProtocol.SAMPLE_RESULT);
                check(waiting.getData().getInt("progress") == 0 && waiting.getData().getInt("socksReady") == 0,
                        "Unavailable control was presented as ready");
                SystemClock.set(3000); remote.idle();
                check(Process.killRequests.isEmpty(), "Cold initialization incorrectly timed out as a hung command");
                TorService.control = new TorControlConnection(); send(TorProcessProtocol.SAMPLE);
                Message available = reply(TorProcessProtocol.SAMPLE_RESULT);
                check(available.getData().getInt("socksReady") == 1, "Fresh control did not initialize"); return;
            case "stop-ack":
                ready(); send(TorProcessProtocol.STOP); Message acknowledged = reply(TorProcessProtocol.STOP_ACK);
                check(acknowledged.getData().getLong("seq") == sequence, "Stop ACK has wrong sequence");
                check(TorService.control.disables.get() == 1, "Stop ACK preceded control acknowledgement");
                check(Process.killRequests.isEmpty(), "Control ACK is not process-death evidence");
                killAt(3000); return;
            case "failed-disable-no-ack":
                ready(); TorService.control.mode = TorControlConnection.Mode.FAIL_DISABLE;
                send(TorProcessProtocol.STOP); reply(TorProcessProtocol.ERROR);
                check(noReply(TorProcessProtocol.STOP_ACK), "Failed disable produced an ACK"); killAt(3000); return;
            case "stop-before-start":
                create(); onMain(() -> service.onUnbind(new Intent()));
                send(TorProcessProtocol.START); remote.idle();
                check(TorService.control.enables.get() == 0, "Late start revived a stopped incarnation");
                killAt(3000); return;
            case "protocol-reply-schema":
                create(); send(TorProcessProtocol.START); Message result = reply(TorProcessProtocol.SAMPLE_RESULT);
                check(result.getData().size() == 8, "Sample schema changed");
                result.getData().putString("raw", "native diagnostic");
                check(!TorProcessProtocol.validReply(result), "Extra reply data was trusted");
                result.getData().remove("raw"); result.sendingUid = Process.UID + 1;
                check(!TorProcessProtocol.validReply(result), "Foreign-UID reply was trusted"); return;
            default: throw new AssertionError("Unknown scenario " + scenario);
        }
    }

    private void identityRejected() throws Exception {
        onMain(service::onCreate); check(TorService.creates.get() == 0, "Identity failure started native lifecycle");
        check(service.onBind(new Intent()) == null, "Identity failure exposed control Binder");
        onMain(service::onDestroy); SystemClock.set(100000);
        check(Process.killRequests.isEmpty(), "Unverified/main process received a kill request");
    }

    private void configurationRejected() throws Exception {
        create(); send(TorProcessProtocol.START); reply(TorProcessProtocol.ERROR);
        check(TorService.control.enables.get() == 0, "Rejected listener configuration or directory enabled networking");
        check(noReply(TorProcessProtocol.SAMPLE_RESULT), "Rejected listener configuration or directory advertised readiness");
        killAt(3000);
    }

    private void preparationRejected() throws Exception {
        onMain(service::onCreate);
        check(TorService.creates.get() == 0, "Failed private path preparation started native lifecycle");
        check(service.onBind(new Intent()) == null, "Failed private path preparation exposed Binder");
        check(TorService.control.socketConfigurations.get() == 0 && TorService.control.enables.get() == 0,
                "Failed private path preparation opened listener or network");
        killAt(3000);
    }

    private void create() throws Exception {
        onMain(service::onCreate);
        check(TorService.creates.get() == 1, "Expected exactly one native lifecycle start");
        check(TorService.configAtCreate.contains("DisableNetwork 1\n"), "Startup config was not offline");
        check(TorService.configAtCreate.contains("SocksPort 0\n"), "Startup config exposed a SOCKS listener");
        check(!TorService.configAtCreate.contains("127.0.0.1"), "Startup config retained TCP loopback endpoint");
        check(TorService.preparedAtCreate, "Private SOCKS/control directories must be prepared before native lifecycle entry");
        check(TorSocketPaths.events.contains("prepare:" + Process.UID), "Private path must be prepared before native lifecycle start");
        check(service.onStartCommand(new Intent(), 0, 1) == 2, "Service is not START_NOT_STICKY");
        IBinder binder = service.onBind(new Intent()); check(binder != null, "No private service Binder");
        remote = new Messenger(binder);
    }

    private void ready() throws Exception {
        create(); send(TorProcessProtocol.START);
        Message result = reply(TorProcessProtocol.SAMPLE_RESULT);
        check(result.getData().getInt("progress") == 100 && result.getData().getInt("socksReady") == 1
                && result.getData().getInt("circuit") == 1 && result.getData().getInt("pid") == Process.TOR_PID,
                "Expected sanitized Unix readiness sample with verified private PID");
        check(TorService.socketVerified, "Readiness preceded filesystem socket verification");
    }

    private Message request(int command) {
        return TorProcessProtocol.request(command, 1, ++sequence, SystemClock.elapsedRealtime(), owner);
    }

    private void send(int command) throws Exception { remote.send(request(command)); }

    private Message reply(int expected) throws Exception {
        Message message = replies.poll(3, TimeUnit.SECONDS);
        check(message != null && message.what == expected, "Missing/unexpected reply " + expected);
        check(TorProcessProtocol.validReply(message), "Production reply violates protocol schema");
        return message;
    }

    private boolean noReply(int what) {
        for (Message message : replies) if (message.what == what) return false;
        return true;
    }

    private void killAt(long time) throws Exception { SystemClock.set(time); awaitKill(); }

    private void awaitKill() throws Exception {
        eventually(() -> !Process.killRequests.isEmpty(), "Independent deadline did not request process termination");
        for (int pid : Process.killRequests) check(pid == Process.TOR_PID && pid != Process.MAIN_PID,
                "Termination escaped verified private process");
    }

    private static void entered(CountDownLatch latch) throws Exception {
        check(latch.await(3, TimeUnit.SECONDS), "Fault injection point was not reached");
    }

    private static void onMain(Runnable action) throws Exception {
        Looper.getMainLooper().execute(action).get(3, TimeUnit.SECONDS);
    }

    private static void eventually(BooleanSupplier condition, String failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        check(condition.getAsBoolean(), failure);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
