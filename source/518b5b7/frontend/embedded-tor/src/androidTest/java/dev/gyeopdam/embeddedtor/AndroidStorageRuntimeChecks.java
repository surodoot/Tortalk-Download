package dev.gyeopdam.embeddedtor;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;

/** Real AndroidKeyStore/AtomicFile, confined to a fresh test-only namespace and directory. */
final class AndroidStorageRuntimeChecks {
    private interface Operation {void run()throws Exception;}
    static void run(Context testContext)throws Exception {
        final String suffix=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        final String namespace="signal-"+suffix;
        final File directory=new File(testContext.getNoBackupFilesDir(),"runtime-fixture-"+suffix);
        require(directory.mkdir(),"FIXTURE_DIRECTORY_CREATE");
        Context isolated=new ContextWrapper(testContext){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getNoBackupFilesDir(){return directory;}
            @Override public String getPackageName(){return super.getPackageName()+".runtimefixture";}
        };
        final String alias=isolated.getPackageName()+".tortalk."+namespace+".aes256gcm.v1";
        final File base=new File(directory,"tortalk-"+namespace+"-v1.enc");
        final KeystoreBlobStore store=new KeystoreBlobStore(isolated,namespace);
        byte[] payload="synthetic-runtime-private-record-do-not-log-0123456789".getBytes(StandardCharsets.UTF_8);
        try {
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            require(!keys.containsAlias(alias),"FRESH_FIXTURE_ALIAS");
            require(store.read()==null&&!keys.containsAlias(alias),"READ_MUST_NOT_GENERATE_KEY");
            store.write(payload);require(keys.containsAlias(alias),"ANDROID_KEY_CREATED");
            require(Arrays.equals(payload,new KeystoreBlobStore(isolated,namespace).read()),"REOPEN_ROUNDTRIP");
            byte[] first=read(base);require(!contains(first,payload),"NO_PLAINTEXT_FILE");
            store.write(payload);byte[] second=read(base);require(!Arrays.equals(first,second),"FRESH_RANDOMIZED_ENVELOPE");
            expect(KeystoreBlobStore.Code.IDENTITY_MISMATCH,()->store.write(new byte[]{9},previous->{throw new KeystoreBlobStore.StorageException(KeystoreBlobStore.Code.IDENTITY_MISMATCH);}));
            require(Arrays.equals(payload,store.read()),"VALIDATOR_FAILURE_PRESERVES_COMMIT");
            AtomicFile atomic=new AtomicFile(base);FileOutputStream interrupted=atomic.startWrite();
            interrupted.write(new byte[]{0,1,2,3});interrupted.getFD().sync();atomic.failWrite(interrupted);
            require(Arrays.equals(payload,store.read()),"ATOMICFILE_FAILED_WRITE_RECOVERY");
            byte[] original=read(base);byte[] corrupted=original.clone();corrupted[corrupted.length-1]^=1;write(base,corrupted);
            expect(KeystoreBlobStore.Code.CORRUPT_STATE,store::read);
            expect(KeystoreBlobStore.Code.CORRUPT_STATE,()->store.write(payload));
            require(Arrays.equals(corrupted,read(base)),"CORRUPTION_MUST_NOT_RESET");
            write(base,original);require(Arrays.equals(payload,store.read()),"RESTORED_SYNTHETIC_ENVELOPE");
            keys.deleteEntry(alias);
            expect(KeystoreBlobStore.Code.KEY_UNAVAILABLE,store::read);
            expect(KeystoreBlobStore.Code.KEY_UNAVAILABLE,()->store.write(payload));
            require(Arrays.equals(original,read(base)),"MISSING_KEY_MUST_PRESERVE_CIPHERTEXT");
            store.clear();require(store.read()==null&&!keys.containsAlias(alias),"EXPLICIT_CLEAR");
            require(!base.exists()&&!new File(base+".bak").exists()&&!new File(base+".new").exists(),"CLEAR_RECOVERY_RESIDUES");
        } finally {
            Arrays.fill(payload,(byte)0);
            // Only this invocation's random synthetic namespace is ever cleared.
            store.clear();File[] files=directory.listFiles();if(files!=null)for(File file:files)require(file.delete(),"FIXTURE_FILE_CLEANUP");
            require(directory.delete(),"FIXTURE_DIRECTORY_CLEANUP");
        }
    }
    private static void expect(KeystoreBlobStore.Code code,Operation operation)throws Exception {
        try{operation.run();throw new AssertionError("EXPECTED_STORAGE_REJECTION");}
        catch(KeystoreBlobStore.StorageException failure){require(failure.code==code,"FIXED_STORAGE_ERROR_CODE");}
    }
    private static byte[] read(File file)throws IOException {
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] bytes=new byte[1024];int count;while((count=in.read(bytes))!=-1){if(out.size()+count>8192)throw new IOException("FIXTURE_FILE_LIMIT");out.write(bytes,0,count);}return out.toByteArray();
        }
    }
    private static void write(File file,byte[] bytes)throws IOException {try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}}
    private static boolean contains(byte[] bytes,byte[] marker){outer:for(int i=0;i<=bytes.length-marker.length;i++){for(int j=0;j<marker.length;j++)if(bytes[i+j]!=marker[j])continue outer;return true;}return false;}
    private static void require(boolean condition,String code){TorTalkRuntimeInstrumentation.require(condition,code);}
}
