package android.os;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
public class Binder implements IBinder {
 private final List<DeathRecipient> deaths=new ArrayList<>();private boolean alive=true;
 private final Consumer<Message> receiver;
 public Binder(){this(message->{});}public Binder(Consumer<Message> receiver){this.receiver=receiver;}
 public void linkToDeath(DeathRecipient recipient,int flags)throws RemoteException{if(!alive)throw new RemoteException();deaths.add(recipient);}
 public boolean unlinkToDeath(DeathRecipient recipient,int flags){return deaths.remove(recipient);}
 public boolean isBinderAlive(){return alive;}
 public void deliver(Message message)throws RemoteException{if(!alive)throw new RemoteException();new Handler(Looper.getMainLooper()).post(()->receiver.accept(message));}
 public void die(){if(!alive)return;alive=false;for(DeathRecipient recipient:new ArrayList<>(deaths))recipient.binderDied();deaths.clear();}
}
