package dev.gyeopdam.embeddedtor;

import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit host seam: real readiness leases, synthetic fixed time, no Guardian service or JNI. */
final class EmbeddedTorController {
    private TorReadinessGate gate=new TorReadinessGate("/fixture/tor-socks/unready",HostTorSockets.UID);
    private String expectedPath="/fixture/tor-socks/unready";
    private final Set<Runnable> cancellations=new LinkedHashSet<>();
    private long epoch;
    private long now=1000;
    void addCancellation(Runnable callback) { requireMain();cancellations.add(callback); }
    void removeCancellation(Runnable callback) { requireMain();cancellations.remove(callback); }
    TorReadinessGate.ReadyLease lease() { return gate.readyLease(now); }
    boolean current(TorReadinessGate.ReadyLease lease) { return gate.isCurrent(lease,now); }
    void ready(int port) {
        requireMain();String path=HostTorSockets.endpoint(port).path;
        if(!expectedPath.equals(path)){expectedPath=path;gate=new TorReadinessGate(path,HostTorSockets.UID);}
        epoch=gate.begin(now);
        gate.sample(epoch,now,"NOTICE BOOTSTRAP PROGRESS=100 TAG=done","1",HostTorSockets.endpoint(port).controlListener(),HostTorSockets.PID);
    }
    void revoke(boolean notify) {
        requireMain();gate.stop();
        if(notify)for(Runnable callback:new ArrayList<>(cancellations))callback.run();
    }
    void listenerChange(int port) {
        requireMain();gate.sample(epoch,now,"NOTICE BOOTSTRAP PROGRESS=100 TAG=done","1",HostTorSockets.endpoint(port).controlListener(),HostTorSockets.PID);
    }
    int registrations() { return cancellations.size(); }
    private static void requireMain() { if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("HOST_CONTROLLER_MAIN_REQUIRED"); }
}
