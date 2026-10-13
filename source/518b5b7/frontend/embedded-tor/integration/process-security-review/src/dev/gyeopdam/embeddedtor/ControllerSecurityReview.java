package dev.gyeopdam.embeddedtor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Adversarial lifecycle checks against the production controller and explicit host doubles. */
public final class ControllerSecurityReview {
    private static final class DenyingContext extends Context {
        boolean denyStop, denyUnbind;
        DenyingContext(java.io.File directory) { super(directory); }
        @Override public boolean stopService(Intent intent) {
            if (denyStop) throw new SecurityException("Synthetic stop authority denial");
            return super.stopService(intent);
        }
        @Override public void unbindService(ServiceConnection connection) {
            if (denyUnbind) throw new SecurityException("Synthetic unbind authority denial");
            super.unbindService(connection);
        }
    }
    public static void main(String[] args) throws Exception {
        Looper.getMainLooper();
        java.nio.file.Path directory = Files.createTempDirectory("tor-controller-security-");
        DenyingContext context = new DenyingContext(directory.toFile());
        EmbeddedTorController controller = EmbeddedTorController.get(context);
        controller.attachForeground(); controller.start(); context.connect(); Handler.drain();
        Message start = context.requests.remove(0);
        replyReady(start); TorReadinessGate.ReadyLease initialLease = controller.lease();
        check(initialLease != null, "No initial readiness");
        AtomicInteger cancellations = new AtomicInteger();
        controller.addCancellation(cancellations::incrementAndGet);
        Binder original = context.remote;
        ServiceConnection originalConnection = context.connection;
        switch (args[0]) {
            case "probe-death-before-main-drain":
                controller.requestProbe();
                FixedOnionProbe probe = FixedOnionProbe.calls.get(0);
                check(probe.ready.getAsBoolean(), "Probe did not receive initial authority");
                original.die();
                check(!probe.ready.getAsBoolean(), "Probe retained remote authority until main callbacks drained");
                Handler.drain();
                check(controller.canStart(), "Probe cleanup lost exact process-death proof");
                break;
            case "death-before-main-drain":
                original.die();
                check(!controller.current(initialLease) && controller.lease() == null,
                        "Exact remote death retained transport authority until main callbacks drained");
                Handler.drain();
                check(controller.canStart() && cancellations.get() == 1,
                        "Deferred main cleanup failed after immediate lease revocation");
                break;
            case "wrong-binder-death":
                Binder unrelated = new Binder();
                context.connection.onServiceConnected(new ComponentName(), unrelated);
                Handler.drain();
                check(!controller.current(initialLease) && cancellations.get() == 1, "Replacement did not revoke");
                unrelated.die(); Handler.drain();
                check(!controller.canStart(), "Wrong Binder death authorized a new incarnation");
                original.die(); Handler.drain();
                check(controller.canStart(), "Exact retained Binder death did not authorize explicit retry");
                break;
            case "denied-stop-unbind":
                context.denyStop = true; context.denyUnbind = true;
                controller.stop(); Handler.drain();
                check(!controller.current(initialLease) && cancellations.get() == 1, "Denied stop retained API authority");
                SystemClock.advance(10000); Handler.drain();
                check(!controller.canStart() && controller.nativeStopState() ==
                        EmbeddedTorController.NativeStopState.TERMINATION_UNCONFIRMED,
                        "Denied stop or elapsed deadline fabricated death");
                original.die(); Handler.drain();
                check(controller.canStart(), "Exact Binder death was lost after stop denial");
                break;
            case "old-callbacks-new-generation":
                controller.stop(); Handler.drain(); original.die(); Handler.drain();
                context.requests.clear(); controller.start(); context.connect(); Handler.drain();
                Message nextStart = context.requests.remove(0); replyReady(nextStart);
                TorReadinessGate.ReadyLease current = controller.lease();
                originalConnection.onServiceDisconnected(new ComponentName());
                originalConnection.onBindingDied(new ComponentName());
                originalConnection.onNullBinding(new ComponentName());
                originalConnection.onServiceConnected(new ComponentName(), original);
                replyReady(start); Handler.drain();
                check(controller.current(current) && context.starts == 2 && context.requests.isEmpty(),
                        "Old callback changed the current generation");
                break;
            default: throw new AssertionError("Unknown scenario");
        }
        Files.delete(directory);
        System.out.println("PASS " + args[0] + " (host doubles only)");
    }
    private static void replyReady(Message request) throws Exception {
        Bundle data = request.getData();
        request.replyTo.send(TorProcessProtocol.sample(data.getLong("epoch"), data.getLong("seq"),
                SystemClock.elapsedRealtime(), 100, 1, 1, android.os.Process.TOR_PID)); Handler.drain();
    }
    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
}
