package android.os;
public final class Message {
 public int what,arg1,arg2,sendingUid; public Object obj;public Messenger replyTo;private Bundle data;
 public static Message obtain(){return new Message();}public void setData(Bundle value){data=value;}
 public Bundle getData(){if(data==null)data=new Bundle();return data;}public Bundle peekData(){return data;}
}
