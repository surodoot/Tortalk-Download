package android.os;
public final class Message {
    public int what, arg1, arg2, sendingUid = -1;
    public Object obj;
    public Messenger replyTo;
    private Bundle data;
    public static Message obtain() { return new Message(); }
    public Bundle peekData() { return data; }
    public Bundle getData() { if (data == null) data = new Bundle(); return data; }
    public void setData(Bundle value) { data = value; }
}
