package dev.gyeopdam.embeddedtor;
import android.content.Context;
import java.io.*;
import java.util.Arrays;
import java.util.Base64;
/** Native encrypted server admission configuration. No credential ever belongs in web preferences. */
final class ServerConfigStore {
    static final class Config {
        final String serverOnion,registrationToken;
        Config(String origin,String token){serverOnion=origin;registrationToken=token;}
        @Override public String toString(){return "ServerConfig([redacted])";}
    }
    private final KeystoreBlobStore blob;
    ServerConfigStore(Context context){blob=new KeystoreBlobStore(context,"config");}
    synchronized Config load()throws IOException{
        byte[] bytes=blob.read();if(bytes==null)return null;
        try{return decode(bytes);}finally{Arrays.fill(bytes,(byte)0);}
    }
    /** Empty token preserves only a same-origin existing admission code; changing origin never reuses it. */
    synchronized void save(String origin,String token)throws IOException{
        String canonical=OnionApiRequest.canonicalOrigin(origin);if(!canonical.equals(origin))throw new IOException("INVALID_ORIGIN");
        if(token==null)token="";validateToken(token);
        byte[] previous=blob.read();byte[] next=null;
        try{
            Config old=previous==null?null:decode(previous);
            String effective=token.isEmpty()&&old!=null&&old.serverOnion.equals(origin)?old.registrationToken:token;
            next=encode(new Config(origin,effective));
            final byte[] expected=previous;
            blob.write(next,current->{if(!Arrays.equals(expected,current))throw new KeystoreBlobStore.StorageException(KeystoreBlobStore.Code.IDENTITY_MISMATCH);});
        }finally{if(previous!=null)Arrays.fill(previous,(byte)0);if(next!=null)Arrays.fill(next,(byte)0);}
    }
    synchronized void clear()throws IOException{blob.clear();}
    static byte[] encode(Config value)throws IOException{
        if(!OnionApiRequest.canonicalOrigin(value.serverOnion).equals(value.serverOnion))throw new IOException("INVALID_ORIGIN");validateToken(value.registrationToken);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x54544346);out.writeByte(1);out.writeUTF(value.serverOnion);out.writeUTF(value.registrationToken);out.flush();return bytes.toByteArray();
    }
    static Config decode(byte[] bytes)throws IOException{
        if(bytes==null||bytes.length>1024)throw new IOException("CORRUPT_CONFIG");
        try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0x54544346||in.readUnsignedByte()!=1)throw new IOException("CORRUPT_CONFIG");
            Config value=new Config(in.readUTF(),in.readUTF());if(in.read()!=-1||!Arrays.equals(bytes,encode(value)))throw new IOException("CORRUPT_CONFIG");return value;
        }catch(IllegalArgumentException|EOFException invalid){throw new IOException("CORRUPT_CONFIG");}
    }
    private static void validateToken(String token)throws IOException{
        if(token==null||token.isEmpty())return;
        try{if(!token.matches("tt_join_[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException();String raw=token.substring(8);byte[] bytes=Base64.getUrlDecoder().decode(raw);
            if(bytes.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw))throw new IllegalArgumentException();
        }catch(IllegalArgumentException invalid){throw new IOException("INVALID_ADMISSION_CODE");}
    }
}
