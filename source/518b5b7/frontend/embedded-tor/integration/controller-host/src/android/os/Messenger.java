package android.os;
public final class Messenger {
 private final IBinder binder;
 public Messenger(Handler handler){binder=new Binder(handler::dispatchMessage);}public Messenger(IBinder value){binder=value;}
 public IBinder getBinder(){return binder;}
 public void send(Message message)throws RemoteException{message.sendingUid=Process.myUid();binder.deliver(message);}
}
