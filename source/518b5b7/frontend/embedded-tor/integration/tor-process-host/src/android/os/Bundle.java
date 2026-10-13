package android.os;
import java.util.HashMap;
public final class Bundle extends HashMap<String, Object> {
    public void putLong(String key, long value) { put(key, value); }
    public long getLong(String key) { Object value = get(key); return value instanceof Long ? (Long)value : 0; }
    public void putInt(String key, int value) { put(key, value); }
    public int getInt(String key) { Object value = get(key); return value instanceof Integer ? (Integer)value : 0; }
    public void putString(String key, String value) { put(key, value); }
    public void setClassLoader(ClassLoader loader) { }
    public boolean hasFileDescriptors() { return false; }
}
