package dev.gyeopdam.embeddedtor;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import java.nio.file.Files;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.torproject.jni.TorService;

/** Independent adversarial host checks; all Android, native, and kill operations are doubles. */
public final class RemoteSecurityReview {
    public static void main(String[] args) throws Exception {
        TorService.configFile = Files.createTempFile("tor-security-review-", ".torrc").toFile();
        TorService.configFile.deleteOnExit();
        TorProcessService service = new TorProcessService();
        BlockingQueue<Message> replies = new LinkedBlockingQueue<>();
        Messenger owner = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            replies.add(message); return true;
        }));
        Messenger foreign = new Messenger(new Handler(Looper.getMainLooper(), message -> true));
        Looper.getMainLooper().execute(service::onCreate).get(3, TimeUnit.SECONDS);
        Messenger remote = new Messenger(service.onBind(new Intent()));
        remote.send(TorProcessProtocol.request(TorProcessProtocol.START, 1, 1, 1000, owner));
        Message ready = replies.poll(3, TimeUnit.SECONDS);
        check(ready != null && ready.what == TorProcessProtocol.SAMPLE_RESULT, "Owner failed to claim");
        Message attack;
        switch (args[0]) {
            case "foreign-start":
                attack = TorProcessProtocol.request(TorProcessProtocol.START, 2, 2, 1000, foreign);
                break;
            case "foreign-malformed":
                attack = TorProcessProtocol.request(TorProcessProtocol.SAMPLE, 1, 2, 1000, foreign);
                attack.getData().putString("injected", "reject before policy mutation");
                break;
            case "missing-owner":
                attack = TorProcessProtocol.request(TorProcessProtocol.STOP, 1, 2, 1000, owner);
                attack.replyTo = null;
                break;
            case "foreign-renew-no-lease":
                SystemClock.set(3900);
                remote.send(TorProcessProtocol.request(TorProcessProtocol.RENEW, 1, 999, 3900, foreign));
                remote.idle(); SystemClock.set(5000);
                long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (service.stopRequests.get() == 0 && System.nanoTime() < waitUntil) Thread.sleep(5);
                check(service.stopRequests.get() > 0, "Foreign owner extended the legitimate owner's lease");
                System.out.println("PASS foreign-renew-no-lease (host doubles only)"); return;
            case "wrong-uid-active-owner":
                attack = TorProcessProtocol.request(TorProcessProtocol.STOP, 1, 2, 1000, owner);
                remote.sendFromUid(attack, Process.UID + 1); remote.idle();
                check(service.onBind(new Intent()) != null && service.stopRequests.get() == 0,
                        "Foreign UID stopped an active owner using its reply Binder");
                System.out.println("PASS wrong-uid-active-owner (host doubles only)"); return;
            case "owner-malformed":
                attack = TorProcessProtocol.request(TorProcessProtocol.SAMPLE, 1, 2, 1000, owner);
                attack.getData().putString("injected", "owner must fail closed");
                remote.send(attack); remote.idle();
                check(service.onBind(new Intent()) == null, "Malformed owner retained live service");
                System.out.println("PASS owner-malformed (host doubles only)"); return;
            case "changed-self-pid":
                Process.pid = Process.MAIN_PID;
                Looper.getMainLooper().execute(service::onDestroy).get(3, TimeUnit.SECONDS);
                check(Process.killRequests.isEmpty(), "Changed self identity authorized a kill");
                System.out.println("PASS changed-self-pid (host doubles only)"); return;
            default: throw new AssertionError("Unknown scenario");
        }
        remote.send(attack); remote.idle(); Looper.getMainLooper().idle();
        check(service.onBind(new Intent()) != null && service.stopRequests.get() == 0
                && TorService.control.disables.get() == 0, "Foreign/missing owner stopped the legitimate session");
        SystemClock.set(1100);
        remote.send(TorProcessProtocol.request(TorProcessProtocol.SAMPLE, 1, 2, 1100, owner));
        Message sample = replies.poll(3, TimeUnit.SECONDS);
        check(sample != null && sample.what == TorProcessProtocol.SAMPLE_RESULT,
                "Legitimate owner lost command authority");
        System.out.println("PASS " + args[0] + " (host doubles only)");
    }
    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
}
