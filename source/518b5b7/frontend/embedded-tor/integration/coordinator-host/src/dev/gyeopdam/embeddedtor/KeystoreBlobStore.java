package dev.gyeopdam.embeddedtor;
import android.content.Context;
import java.io.*;
import java.util.*;
final class KeystoreBlobStore  {
    static final Map<String,byte[]> DATA=new HashMap<>();
    static String failNamespace;
    static int failures;
    interface Validator  {
        void validate(byte[] previous)throws StorageException;
    }
    enum Code  {
        INVALID_INPUT,CORRUPT_STATE,KEY_UNAVAILABLE,IO_FAILED,ORIGIN_MISMATCH,IDENTITY_MISMATCH
    }
    static class StorageException extends IOException  {
        final Code code;
        StorageException(Code c) {
            super(c.name());
            code=c;
        }
    }
    final String ns;
    KeystoreBlobStore(Context c,String ns) {
        this.ns=ns;
    }
    byte[] read()throws StorageException  {
        synchronized(DATA) {
            byte[] b=DATA.get(ns);
            return b==null?null:b.clone();
        }
    }
    void write(byte[] bytes)throws StorageException  {
        write(bytes,p-> {
        }
        );
    }
    void write(byte[] bytes,Validator validator)throws StorageException  {
        synchronized(DATA) {
            if(ns.equals(failNamespace)&&failures>0) {
                failures--;
                throw new StorageException(Code.IO_FAILED);
            }
            validator.validate(read());
            DATA.put(ns,bytes.clone());
        }
    }
    void clear()throws StorageException  {
        synchronized(DATA) {
            DATA.remove(ns);
        }
    }
}
