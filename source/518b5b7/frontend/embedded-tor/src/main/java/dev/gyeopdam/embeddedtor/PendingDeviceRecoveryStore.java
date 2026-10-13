package dev.gyeopdam.embeddedtor;

import android.content.Context;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Explicit recovery only. All calls run on AccountWorkQueue; every capability stays native/encrypted. */
public final class PendingDeviceRecoveryStore {
    private final KeystoreBlobStore blob;
    private static final Object MUTATION_LOCK=new Object();
    private static long mutationEpoch;
    static long mutationEpoch(){synchronized(MUTATION_LOCK){return mutationEpoch;}}
    static boolean dispatchIfCurrent(long expected,Runnable dispatch){synchronized(MUTATION_LOCK){if(expected!=mutationEpoch)return false;dispatch.run();return true;}}

    public PendingDeviceRecoveryStore(Context context){blob=new KeystoreBlobStore(context,"device-recovery");}
    public synchronized Pending load()throws RecoveryException {
        byte[] bytes=null;try{bytes=blob.read();return bytes==null?null:decode(bytes);}catch(KeystoreBlobStore.StorageException failure){throw mapped(failure);}finally{wipe(bytes);}
    }
    /** Reuses an exact existing checkpoint; never regenerates merely because a response was lost. */
    public synchronized Pending prepare(String origin,String account,String target,String oldCapability)throws RecoveryException {
        if(!SessionSchema.isCanonicalOrigin(origin)||!PendingDeviceRecoverySchema.id(account)||!PendingDeviceRecoverySchema.id(target)||!SessionSchema.token(oldCapability,"gd_acct_"))throw fail(Code.INVALID_INPUT);
        byte[] before=null,next=null;
        try{
            before=blob.read();Pending previous=before==null?null:decode(before);
            if(previous!=null){if(!previous.serverOnion.equals(origin))throw fail(Code.ORIGIN_MISMATCH);if(!previous.accountId.equals(account)||!previous.targetDeviceId.equals(target)||!previous.oldAccountToken.equals(oldCapability))throw fail(Code.PENDING_CONFLICT);return previous;}
            byte[] secret=new byte[32],identity=new byte[16];Pending created;
            try{SecureRandom random=new SecureRandom();String replacement;do{random.nextBytes(secret);replacement="gd_acct_"+Base64.getUrlEncoder().withoutPadding().encodeToString(secret);}while(oldCapability.equals(replacement));
                random.nextBytes(identity);StringBuilder id=new StringBuilder("demo-recovery-");for(byte value:identity){id.append("0123456789abcdef".charAt((value&255)>>>4));id.append("0123456789abcdef".charAt(value&15));}
                created=new Pending(origin,account,target,id.toString(),oldCapability,replacement,false);
            }finally{wipe(secret);wipe(identity);}
            next=encode(created);writeCompared(before,next);return created;
        }catch(KeystoreBlobStore.StorageException failure){throw mapped(failure);}finally{wipe(before);wipe(next);}
    }
    public synchronized Pending markConfirmed(String recoveryId)throws RecoveryException {
        if(!PendingDeviceRecoverySchema.id(recoveryId))throw fail(Code.INVALID_INPUT);byte[] before=null,next=null;
        try{before=blob.read();Pending previous=before==null?null:decode(before);if(previous==null)throw fail(Code.NO_PENDING);if(!recoveryId.equals(previous.recoveryId))throw fail(Code.STATE_CHANGED);if(previous.confirmed)return previous;
            Pending confirmed=new Pending(previous.serverOnion,previous.accountId,previous.targetDeviceId,previous.recoveryId,previous.oldAccountToken,previous.replacementAccountToken,true);next=encode(confirmed);writeCompared(before,next);return confirmed;
        }catch(KeystoreBlobStore.StorageException failure){throw mapped(failure);}finally{wipe(before);wipe(next);}
    }
    /** Explicit acknowledgement after verified server success and recording the new key. */
    public synchronized void acknowledge(String recoveryId)throws RecoveryException {
        if(!PendingDeviceRecoverySchema.id(recoveryId))throw fail(Code.INVALID_INPUT);byte[] before=null,next=null;
        try{before=blob.read();Pending previous=before==null?null:decode(before);if(previous==null)return;if(!recoveryId.equals(previous.recoveryId))throw fail(Code.STATE_CHANGED);if(!previous.confirmed)throw fail(Code.UNCONFIRMED);
            try{next=PendingDeviceRecoverySchema.encode(null,false);}catch(IOException impossible){throw fail(Code.CORRUPT_STATE);}writeCompared(before,next);
        }catch(KeystoreBlobStore.StorageException failure){throw mapped(failure);}finally{wipe(before);wipe(next);}
    }
    /** Readable abandonment is bound to the exact checkpoint shown in the native warning. */
    public synchronized void resetAfterConfirmation(String expectedRecoveryId)throws RecoveryException {
        if(expectedRecoveryId!=null){
            if(!PendingDeviceRecoverySchema.id(expectedRecoveryId))throw fail(Code.INVALID_INPUT);byte[] before=null,next=null;
            try{before=blob.read();Pending previous=before==null?null:decode(before);if(previous==null||!expectedRecoveryId.equals(previous.recoveryId))throw fail(Code.STATE_CHANGED);
                try{next=PendingDeviceRecoverySchema.encode(null,false);}catch(IOException impossible){throw fail(Code.CORRUPT_STATE);}writeCompared(before,next);
            }catch(KeystoreBlobStore.StorageException failure){throw mapped(failure);}finally{wipe(before);wipe(next);}return;
        }
        // AccountWorkQueue serializes this recheck and reset with every account-store operation.
        // A stale corruption warning must never erase a now-readable replacement checkpoint.
        try{load();throw fail(Code.STATE_CHANGED);}catch(RecoveryException failure){
            if(failure.code!=Code.CORRUPT_STATE&&failure.code!=Code.KEY_UNAVAILABLE)throw failure;
            try{synchronized(MUTATION_LOCK){mutationEpoch++;}blob.clear();}catch(KeystoreBlobStore.StorageException clearFailure){throw mapped(clearFailure);}
        }
    }
    private void writeCompared(byte[] before,byte[] next)throws KeystoreBlobStore.StorageException {
        synchronized(MUTATION_LOCK){mutationEpoch++;}
        blob.write(next,current->{if(!Arrays.equals(before,current))throw new KeystoreBlobStore.StorageException(KeystoreBlobStore.Code.IDENTITY_MISMATCH);});
    }
    private static Pending decode(byte[] bytes)throws RecoveryException {try{PendingDeviceRecoverySchema.Record value=PendingDeviceRecoverySchema.decode(bytes);return value.fields==null?null:new Pending(value.fields[0],value.fields[1],value.fields[2],value.fields[3],value.fields[4],value.fields[5],value.confirmed);}catch(IOException|IllegalArgumentException malformed){throw fail(Code.CORRUPT_STATE);}}
    private static byte[] encode(Pending value)throws RecoveryException {try{return PendingDeviceRecoverySchema.encode(new String[]{value.serverOnion,value.accountId,value.targetDeviceId,value.recoveryId,value.oldAccountToken,value.replacementAccountToken},value.confirmed);}catch(IOException malformed){throw fail(Code.CORRUPT_STATE);}}
    public static final class Pending {
        public final String serverOnion,accountId,targetDeviceId,recoveryId,oldAccountToken,replacementAccountToken;public final boolean confirmed;
        public Pending(String origin,String account,String target,String id,String oldCapability,String replacement,boolean confirmed){PendingDeviceRecoverySchema.validate(new String[]{origin,account,target,id,oldCapability,replacement});serverOnion=origin;accountId=account;targetDeviceId=target;recoveryId=id;oldAccountToken=oldCapability;replacementAccountToken=replacement;this.confirmed=confirmed;}
        @Override public String toString(){return "PendingDeviceRecovery([redacted])";}
    }
    public enum Code {INVALID_INPUT,CORRUPT_STATE,KEY_UNAVAILABLE,IO_FAILED,ORIGIN_MISMATCH,IDENTITY_MISMATCH,PENDING_CONFLICT,NO_PENDING,UNCONFIRMED,STATE_CHANGED}
    public static final class RecoveryException extends IOException {private static final long serialVersionUID=1L;public final Code code;private RecoveryException(Code code){super(code.name());this.code=code;}}
    private static RecoveryException fail(Code code){return new RecoveryException(code);}
    private static RecoveryException mapped(KeystoreBlobStore.StorageException failure){return fail(Code.valueOf(failure.code.name()));}
    private static void wipe(byte[] value){if(value!=null)Arrays.fill(value,(byte)0);}
    @Override public String toString(){return "PendingDeviceRecoveryStore([redacted])";}
}
