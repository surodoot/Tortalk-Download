package android.os;
import java.util.HashMap;
public final class Bundle extends HashMap<String,Object> {
 public void putLong(String key,long value){put(key,value);} public long getLong(String key){Object x=get(key);return x instanceof Long?(Long)x:0;}
 public void putInt(String key,int value){put(key,value);} public int getInt(String key){Object x=get(key);return x instanceof Integer?(Integer)x:0;}
 public void putString(String key,String value){put(key,value);} public void setClassLoader(ClassLoader value){}
 public boolean hasFileDescriptors(){return false;}
}
