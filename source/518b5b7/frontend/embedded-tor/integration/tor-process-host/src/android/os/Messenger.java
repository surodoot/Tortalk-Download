package android.os;
import java.util.concurrent.CountDownLatch;
/** UID capture and queued delivery double. Optional send blocking models outbound IPC failure. */
public final class Messenger {
    public static final class Endpoint extends Binder {
        private final Handler handler;
        public volatile CountDownLatch blockSend;
        public final CountDownLatch sendEntered = new CountDownLatch(1);
        Endpoint(Handler handler) { this.handler = handler; }
        void deliver(Message message, int uid) throws RemoteException {
            if (!isBinderAlive()) throw new RemoteException();
            sendEntered.countDown();
            CountDownLatch blocker = blockSend;
            if (blocker != null) {
                try { blocker.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RemoteException(); }
            }
            message.sendingUid = uid;
            handler.dispatch(message);
        }
    }
    private final Endpoint endpoint;
    public Messenger(Handler handler) { endpoint = new Endpoint(handler); }
    public Messenger(IBinder binder) { endpoint = (Endpoint)binder; }
    public IBinder getBinder() { return endpoint; }
    public void send(Message message) throws RemoteException { endpoint.deliver(message, Process.myUid()); }
    public void sendFromUid(Message message, int uid) throws RemoteException { endpoint.deliver(message, uid); }
    public void idle() throws Exception { endpoint.handler.looper.idle(); }
}
