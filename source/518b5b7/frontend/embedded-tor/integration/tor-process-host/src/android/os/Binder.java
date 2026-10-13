package android.os;
import java.util.concurrent.CopyOnWriteArrayList;
public class Binder implements IBinder {
    private final CopyOnWriteArrayList<DeathRecipient> deaths = new CopyOnWriteArrayList<>();
    private volatile boolean alive = true;
    public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
        if (!alive) throw new RemoteException(); deaths.add(recipient);
    }
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return deaths.remove(recipient); }
    public boolean isBinderAlive() { return alive; }
    public void die() { alive = false; for (DeathRecipient death : deaths) death.binderDied(); }
}
