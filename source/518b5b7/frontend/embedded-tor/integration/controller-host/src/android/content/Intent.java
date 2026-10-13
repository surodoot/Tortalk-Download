package android.content;
public final class Intent {
    public final Context context;
    public final Class<?> target;
    public Intent(Context context, Class<?> target) { this.context = context; this.target = target; }
}
