package dev.gyeopdam.embeddedtor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import org.torproject.jni.TorService;
import net.freehaven.tor.control.TorControlConnection;
import net.freehaven.tor.control.ConfigEntry;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One disposable, application-owned Tor incarnation. Must only run in package+:tor. */
public final class TorProcessService extends TorService {
    private final AtomicBoolean stopWorkQueued = new AtomicBoolean();
    private final AtomicBoolean errorSent = new AtomicBoolean();
    private volatile TorProcessPolicy policy;
    private volatile Messenger owner;
    private volatile TorProcessProtocol.Request stopRequest;
    private volatile boolean destroyed, nativeStarted, identityVerified;
    private int ownPid, ownUid;
    private String ownProcessName;
    private Handler serviceMain;
    private HandlerThread incomingThread;
    private Messenger incoming;
    private ExecutorService controlWorker;
    private ScheduledExecutorService watchdog;
    private boolean networkEnabled, socksConfigured; // Read/written only by the control worker.
    private String socksPath;
    private final IBinder.DeathRecipient ownerDied = () -> beginStopping(TorProcessProtocol.ERROR_OWNER);

    @Override public void onCreate() {
        // Do not call the upstream onCreate before proving this is the private process.
        // The superclass class initializer loads JNI, but native Tor starts in onCreate.
        if (!verifyOwnProcess()) { stopSelf(); return; }
        serviceMain = new Handler(Looper.getMainLooper());
        policy = new TorProcessPolicy(SystemClock.elapsedRealtime());
        controlWorker = Executors.newSingleThreadExecutor(task -> daemon(task, "tor-process-control"));
        watchdog = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "tor-process-deadline"));
        watchdog.scheduleWithFixedDelay(this::watchDeadlines, 0, 50, TimeUnit.MILLISECONDS);
        incomingThread = new HandlerThread("tor-process-inbox");
        incomingThread.start();
        incoming = new Messenger(new Handler(incomingThread.getLooper(), this::receive));
        try {
            socksPath = TorSocketPaths.socksPath(this);
            TorSocketPaths.prepare(this, ownUid);
            TorSocketPaths.prepareControl(TorService.getTorrc(this), ownUid);
            // The pinned vendor parses its first SOCKS GETINFO as a numeric port. A zero
            // listener safely publishes its auth marker before we configure Unix SOCKS.
            String config = "ClientOnly 1\nDisableNetwork 1\nSocksPort 0\n"
                    + "HTTPTunnelPort 0\nDNSPort 0\nTransPort 0\nControlPort 0\n"
                    + "SafeSocks 1\nSocksSocketsGroupWritable 0\nControlSocketsGroupWritable 0\n"
                    + "AvoidDiskWrites 1\nLog err stderr\n";
            try (FileOutputStream out = new FileOutputStream(TorService.getTorrc(this), false)) {
                out.write(config.getBytes(StandardCharsets.US_ASCII));
                out.getFD().sync();
            }
            nativeStarted = true;
            super.onCreate();
        } catch (Exception | LinkageError unavailable) {
            beginStopping(TorProcessProtocol.ERROR_STARTUP);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // No START_STICKY inherited behavior, intent replay, or start-intent network opt-in.
        if (!identityVerified || !nativeStarted || intent == null) {
            beginStopping(TorProcessProtocol.ERROR_STARTUP);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return identityVerified && !destroyed && policy != null && !policy.isStopping()
                && incoming != null ? incoming.getBinder() : null;
    }

    @Override public boolean onUnbind(Intent intent) {
        beginStopping(TorProcessProtocol.ERROR_OWNER);
        return false;
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (!identityVerified) return;
        beginStopping(0);
        // Upstream owns a main-thread ReentrantLock. Never move this call to the worker.
        // Its synchronous SHUTDOWN can block; the independent deadline remains armed.
        try {
            if (nativeStarted) super.onDestroy();
        } finally {
            terminateOwnProcess();
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        beginStopping(TorProcessProtocol.ERROR_OWNER);
        super.onTaskRemoved(rootIntent);
    }

    private boolean receive(Message message) {
        // Messenger records the caller UID before queueing. Handler Binder identity is local.
        if (message.sendingUid != Process.myUid()) return true;
        // Once claimed, even malformed traffic must first prove the exact owner.
        // A different same-UID binding has no authority to stop the active incarnation.
        Messenger claimedOwner = owner;
        if (claimedOwner != null && (message.replyTo == null
                || !claimedOwner.getBinder().equals(message.replyTo.getBinder()))) return true;
        TorProcessProtocol.Request request = TorProcessProtocol.readRequest(message);
        if (request == null) { beginStopping(TorProcessProtocol.ERROR_PROTOCOL); return true; }
        TorProcessPolicy current = policy;
        if (!identityVerified || destroyed || current == null || current.isStopping()) return true;
        long now = SystemClock.elapsedRealtime();
        if (request.what == TorProcessProtocol.START) {
            if (owner != null) { beginStopping(TorProcessProtocol.ERROR_PROTOCOL); return true; }
            owner = request.owner;
            try {
                owner.getBinder().linkToDeath(ownerDied, 0);
            } catch (RemoteException deadOwner) {
                beginStopping(TorProcessProtocol.ERROR_OWNER);
                return true;
            }
            if (!current.claim(request.epoch, request.seq, request.sentAt, now)) {
                beginStopping(TorProcessProtocol.ERROR_PROTOCOL);
                return true;
            }
            queueSample(request);
            return true;
        }
        Messenger currentOwner = owner;
        if (currentOwner == null || !currentOwner.getBinder().equals(request.owner.getBinder())) {
            // Stale bindings cannot replace or terminate the current owner's incarnation.
            return true;
        }
        if (request.what == TorProcessProtocol.STOP) {
            if (current.acceptStop(request.epoch, request.seq, request.sentAt, now)) {
                stopRequest = request;
                beginStopping(0);
            } else {
                beginStopping(TorProcessProtocol.ERROR_PROTOCOL);
            }
        } else if (!current.renew(request.epoch, request.seq, request.sentAt, now)) {
            beginStopping(TorProcessProtocol.ERROR_PROTOCOL);
        } else if (request.what == TorProcessProtocol.SAMPLE) {
            queueSample(request);
        }
        return true;
    }

    private void queueSample(TorProcessProtocol.Request request) {
        TorProcessPolicy current = policy;
        if (!current.beginCommand(request.seq, SystemClock.elapsedRealtime())) {
            beginStopping(TorProcessProtocol.ERROR_PROTOCOL);
            return;
        }
        // At most one sample can be pending; an extra one fails closed before queueing.
        controlWorker.execute(() -> sample(request));
    }

    private void sample(TorProcessProtocol.Request request) {
        TorProcessPolicy current = policy;
        long sampledAt = SystemClock.elapsedRealtime();
        if (!current.commandCurrent(request.seq, sampledAt)) return;
        try {
            TorControlConnection control = getTorControlConnection();
            if (control == null) {
                // Offline native initialization is asynchronous. No blocking command was sent.
                if (current.finishCommand(request.seq, SystemClock.elapsedRealtime())) {
                    reply(TorProcessProtocol.sample(request.epoch, request.seq, sampledAt, 0, 0, 0, ownPid));
                }
                return;
            }
            final boolean authenticated;
            synchronized (control) {
                // In this pinned vendor version the public marker starts at -1 and is
                // set only after AUTHENTICATE, SETEVENTS and its first SOCKS GETINFO.
                // The following vendor control query publishes that write through this
                // same monitor. Zero means initialized without an active listener.
                int marker = getSocksPort();
                if (marker > 0) throw new IllegalStateException("UNEXPECTED_TCP_SOCKS");
                authenticated = marker == 0;
            }
            if (!authenticated) {
                if (current.finishCommand(request.seq, SystemClock.elapsedRealtime())) {
                    reply(TorProcessProtocol.sample(request.epoch, request.seq, sampledAt, 0, 0, 0, ownPid));
                }
                return;
            }
            if (!socksConfigured) {
                if (!current.commandCurrent(request.seq, SystemClock.elapsedRealtime())) return;
                String expectedConfiguration = "unix:" + socksPath + " OnionTrafficOnly";
                control.setConf("SocksPort", expectedConfiguration);
                if (!current.commandCurrent(request.seq, SystemClock.elapsedRealtime())) return;
                // DisableNetwork suppresses non-control listeners. Verify configuration
                // before enabling, then verify the actual socket before granting READY.
                List<ConfigEntry> configured = control.getConf("SocksPort");
                if (!current.commandCurrent(request.seq, SystemClock.elapsedRealtime())) return;
                if (configured == null || configured.size() != 1 || configured.get(0) == null
                        || !"SocksPort".equals(configured.get(0).key)
                        || !expectedConfiguration.equals(configured.get(0).value)) {
                    beginStopping(TorProcessProtocol.ERROR_CONTROL); return;
                }
                TorSocketPaths.verifyDirectoryForPath(socksPath, ownUid);
                if (!current.commandCurrent(request.seq, SystemClock.elapsedRealtime())) return;
                socksConfigured = true;
            }
            if (!networkEnabled) {
                if (!current.commandCurrent(request.seq, SystemClock.elapsedRealtime())) return;
                control.setConf("DisableNetwork", "0");
                if (!current.commandCurrent(request.seq, SystemClock.elapsedRealtime())) return;
                networkEnabled = true;
            }
            if (!current.commandCurrent(request.seq, SystemClock.elapsedRealtime())) return;
            String bootstrap = control.getInfo("status/bootstrap-phase");
            if (!current.commandCurrent(request.seq, SystemClock.elapsedRealtime())) return;
            String circuit = control.getInfo("status/circuit-established");
            if (!current.commandCurrent(request.seq, SystemClock.elapsedRealtime())) return;
            String listeners = control.getInfo("net/listeners/socks");
            if (bootstrap == null || bootstrap.length() > 4096 || circuit == null || circuit.length() > 8
                    || listeners == null || listeners.length() > 128) {
                beginStopping(TorProcessProtocol.ERROR_CONTROL);
                return;
            }
            // Reuse the existing strict parser, then send only bounded numeric fields.
            TorReadinessGate snapshot = new TorReadinessGate(socksPath, ownUid);
            long sampleEpoch = snapshot.begin(sampledAt);
            snapshot.sample(sampleEpoch, sampledAt, bootstrap, circuit, listeners, ownPid);
            int progress = snapshot.progress();
            int socksReady = snapshot.readyEndpoint(sampledAt) == null ? 0 : 1;
            if (socksReady == 1) TorSocketPaths.verify(new TorSocketEndpoint(socksPath, ownUid, ownPid));
            int established = "1".equals(circuit) ? 1 : 0;
            if (current.finishCommand(request.seq, SystemClock.elapsedRealtime())) {
                reply(TorProcessProtocol.sample(request.epoch, request.seq, sampledAt, progress, socksReady, established, ownPid));
            }
        } catch (Exception | LinkageError unavailable) {
            beginStopping(TorProcessProtocol.ERROR_CONTROL);
        }
    }

    private void beginStopping(int error) {
        TorProcessPolicy current = policy;
        if (!identityVerified || current == null) return;
        current.stop(SystemClock.elapsedRealtime());
        if (error != 0) sendError(error);
        if (controlWorker == null || !stopWorkQueued.compareAndSet(false, true)) return;
        // Do not interrupt jtorctl: interrupted waiters remain queued in that connection.
        controlWorker.execute(() -> {
            try {
                TorControlConnection control = getTorControlConnection();
                if (control != null) {
                    control.setConf("DisableNetwork", "1");
                    TorProcessProtocol.Request stopped = stopRequest;
                    if (stopped != null) {
                        reply(TorProcessProtocol.stopped(stopped.epoch, stopped.seq, SystemClock.elapsedRealtime()));
                    }
                }
            } catch (Exception | LinkageError unavailable) {
                sendError(TorProcessProtocol.ERROR_CONTROL);
            } finally {
                Handler main = serviceMain;
                if (main != null) main.post(this::stopSelf);
            }
        });
        Handler main = serviceMain;
        if (main != null) main.post(this::stopSelf);
    }

    private void watchDeadlines() {
        TorProcessPolicy current = policy;
        if (current == null) return;
        switch (current.expiry(SystemClock.elapsedRealtime())) {
            case NONE:
                return;
            case LEASE:
                // Keep this independent thread free of even one-way Binder sends.
                beginStopping(0);
                return;
            default:
                current.stop(SystemClock.elapsedRealtime());
                // Do not send Binder replies or wait for any control/lifecycle lock here.
                terminateOwnProcess();
        }
    }

    private void sendError(int error) {
        TorProcessPolicy current = policy;
        if (current == null || current.epoch() <= 0 || current.sequence() <= 0
                || !errorSent.compareAndSet(false, true)) return;
        reply(TorProcessProtocol.error(current.epoch(), current.sequence(), error));
    }

    private void reply(Message message) {
        Messenger currentOwner = owner;
        if (currentOwner == null) return;
        try {
            currentOwner.send(message);
        } catch (RemoteException | RuntimeException unavailable) {
            beginStopping(0);
        }
    }

    private boolean verifyOwnProcess() {
        int pid = Process.myPid();
        int uid = Process.myUid();
        String expected = getPackageName() + ":tor";
        try {
            if (getApplicationInfo() == null || getApplicationInfo().uid != uid) return false;
            ActivityManager manager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = manager == null
                    ? null : manager.getRunningAppProcesses();
            if (processes == null) return false;
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process != null && process.pid == pid && process.uid == uid
                        && expected.equals(process.processName)) {
                    ownPid = pid;
                    ownUid = uid;
                    ownProcessName = process.processName;
                    identityVerified = true;
                    return true;
                }
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
        return false;
    }

    private void terminateOwnProcess() {
        // Identity was verified via ActivityManager before native start. A process cannot
        // migrate between Android app processes. Recheck local identity without blocking IPC.
        if (identityVerified && ownPid == Process.myPid() && ownUid == Process.myUid()
                && (getPackageName() + ":tor").equals(ownProcessName)) {
            Process.killProcess(Process.myPid());
        }
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }
}
