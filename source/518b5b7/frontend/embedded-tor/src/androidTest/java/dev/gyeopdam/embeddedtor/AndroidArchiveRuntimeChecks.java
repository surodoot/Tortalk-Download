package dev.gyeopdam.embeddedtor;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Prepared only: real Android Handler, Keystore and Signal JNI; synthetic profile, unstarted Tor. */
final class AndroidArchiveRuntimeChecks {
    private static final String ORIGIN="http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion";
    private static final String DEVICE="demo-device-archive",PEER="demo-account-archive-peer",PEER_DEVICE="demo-device-archive-peer";
    private static final String ROOM="demo-room-archive",EMPTY_ROOM="demo-room-archive-z-empty",WRONG_PEER="demo-account-other-peer";
    private interface MainAction {void run()throws Exception;}
    private interface AsyncAction {void run(MessageCoordinator.Callback callback);}
    private static final class Memory implements SignalClient.StateStorage {
        private byte[] value;
        @Override public synchronized byte[] load(){return value==null?null:value.clone();}
        @Override public synchronized void save(byte[] next){clear();value=next.clone();}
        synchronized void clear(){if(value!=null)Arrays.fill(value,(byte)0);value=null;}
    }
    private static final class Completion implements MessageCoordinator.Callback {
        final CountDownLatch latch=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger();
        final AtomicReference<String> error=new AtomicReference<>();
        final AtomicBoolean onMain=new AtomicBoolean();
        @Override public void complete(String code){
            calls.incrementAndGet();error.set(code);onMain.set(Looper.myLooper()==Looper.getMainLooper());latch.countDown();
        }
        void await(String expected)throws Exception {
            require(latch.await(15,TimeUnit.SECONDS),"ARCHIVE_CALLBACK_TIMEOUT");
            require(calls.get()==1&&onMain.get(),"ARCHIVE_MAIN_CALLBACK_REQUIRED");
            require(expected.equals(error.get()),"ARCHIVE_CALLBACK_CODE");
        }
    }
    static void run(Instrumentation instrumentation)throws Exception {
        Context target=instrumentation.getTargetContext();
        String suffix=UUID.randomUUID().toString();
        String account="demo-account-archive-"+suffix;
        String namespace=SignalProfile.namespace(ORIGIN,account);
        File directory=new File(target.getNoBackupFilesDir(),"archive-runtime-fixture-"+suffix);
        require(directory.mkdir(),"ARCHIVE_FIXTURE_DIRECTORY_CREATE");
        Context isolated=new ContextWrapper(target){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getNoBackupFilesDir(){return directory;}
            @Override public String getPackageName(){return super.getPackageName()+".archivefixture";}
        };
        String alias=isolated.getPackageName()+".tortalk."+namespace+".aes256gcm.v1";
        File base=new File(directory,"tortalk-"+namespace+"-v1.enc");
        File lock=new File(directory,"tortalk-"+namespace+"-v1.lock");
        KeystoreBlobStore store=new KeystoreBlobStore(isolated,namespace);
        AtomicReference<MessageCoordinator> selected=new AtomicReference<>();
        AtomicReference<TorApiClient> api=new AtomicReference<>();
        AtomicReference<EmbeddedTorController> controller=new AtomicReference<>();
        AtomicInteger sessionChanges=new AtomicInteger();
        byte[] snapshot=null;
        boolean ownsFixture=false;
        try {
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            require(!keys.containsAlias(alias),"ARCHIVE_FRESH_KEY_ALIAS_REQUIRED");
            ownsFixture=true;
            require(store.read()==null&&!keys.containsAlias(alias),"ARCHIVE_ABSENT_READ_NO_KEY");
            byte[] fixture=syntheticState(account);
            try {store.write(fixture);} finally {Arrays.fill(fixture,(byte)0);}
            require(keys.containsAlias(alias)&&base.isFile(),"ARCHIVE_SYNTHETIC_PROFILE_SAVED");
            snapshot=read(base);
            AppSessionStore.Session session=new AppSessionStore.Session(ORIGIN,account,"",DEVICE,
                    token("gd_dev_"),token("gd_sess_"));
            main(instrumentation,()->{
                EmbeddedTorController tor=EmbeddedTorController.get(target);controller.set(tor);
                assertOffline(tor);
                TorApiClient client=new TorApiClient(tor);api.set(client);
                require(client.configureOnion(ORIGIN),"ARCHIVE_CONFIGURE_SYNTHETIC_ORIGIN");
                MessageCoordinator coordinator=new MessageCoordinator(isolated,client,new MessageCoordinator.Listener(){
                    @Override public void sessionChanged(AppSessionStore.Session value){sessionChanges.incrementAndGet();}
                    @Override public void changed(){ }
                });
                selected.set(coordinator);coordinator.bindSession(session);
                require(!coordinator.isReady(ROOM)&&coordinator.currentRoom().isEmpty(),"ARCHIVE_BIND_NOT_LIVE");
            });
            MessageCoordinator coordinator=selected.get();
            JSONObject first=list(instrumentation,coordinator,"","",1);
            JSONArray rooms=first.getJSONArray("rooms");
            require(rooms.length()==1&&ROOM.equals(rooms.getJSONObject(0).getString("room_id"))
                    &&PEER.equals(rooms.getJSONObject(0).getString("peer_id"))
                    &&rooms.getJSONObject(0).getInt("message_count")==3
                    &&rooms.getJSONObject(0).getBoolean("read_only"),"ARCHIVE_COORDINATOR_ROOM_ASSOCIATION");
            require(ROOM.equals(first.getString("next_after")),"ARCHIVE_COORDINATOR_ROOM_CURSOR");
            JSONObject last=list(instrumentation,coordinator,PEER,first.getString("next_after"),1);
            require(last.getJSONArray("rooms").length()==1
                    &&EMPTY_ROOM.equals(last.getJSONArray("rooms").getJSONObject(0).getString("room_id"))
                    &&last.getString("next_after").isEmpty(),"ARCHIVE_COORDINATOR_ROOM_TERMINAL");
            require(list(instrumentation,coordinator,WRONG_PEER,"",40).getJSONArray("rooms").length()==0,
                    "ARCHIVE_COORDINATOR_PEER_FILTER");
            await(instrumentation,callback->coordinator.openLocalRoom(ROOM,WRONG_PEER,callback),"LOCAL_ROOM_NOT_FOUND");
            main(instrumentation,()->require(coordinator.currentRoom().isEmpty()
                    &&coordinator.messagesForRoom(ROOM).length()==0&&!coordinator.isReady(ROOM),"ARCHIVE_WRONG_PEER_HIDDEN"));
            Completion opened=new Completion(),blockedWhileOpening=new Completion();
            main(instrumentation,()->{
                coordinator.openLocalRoom(ROOM,PEER,"",2,opened);
                // This main-thread action cannot process the queued read callback yet.
                require(!coordinator.isReady(ROOM),"ARCHIVE_OPENING_NOT_READY");
                coordinator.enroll(blockedWhileOpening);
            });
            blockedWhileOpening.await("LOCAL_ARCHIVE_READ_ONLY");opened.await("");
            AtomicReference<String> cursor=new AtomicReference<>();
            Set<String> ids=new HashSet<>();
            main(instrumentation,()->{
                require(coordinator.isLocalArchive(ROOM)&&ROOM.equals(coordinator.currentRoom())
                        &&!coordinator.isReady(ROOM)&&coordinator.pendingPeer()==null,"ARCHIVE_SUCCESS_NOT_LIVE");
                JSONArray latest=coordinator.messagesForRoom(ROOM);
                require(latest.length()==2,"ARCHIVE_COORDINATOR_HISTORY_LIMIT");
                collect(latest,ids);cursor.set(coordinator.localArchiveNextBefore());
                require(!cursor.get().isEmpty(),"ARCHIVE_COORDINATOR_HISTORY_CURSOR");
                require(coordinator.messagesForRoom(EMPTY_ROOM).length()==0,"ARCHIVE_ROOM_SCOPED_VIEW");
                latest.getJSONObject(0).put("text","synthetic-view-copy-change");
                require(!"synthetic-view-copy-change".equals(coordinator.messagesForRoom(ROOM).getJSONObject(0).getString("text")),
                        "ARCHIVE_VIEW_DEFENSIVE_COPY");
            });
            await(instrumentation,callback->coordinator.openLocalRoom(ROOM,PEER,cursor.get(),2,callback),"");
            main(instrumentation,()->{
                JSONArray older=coordinator.messagesForRoom(ROOM);
                require(older.length()==1&&coordinator.localArchiveNextBefore().isEmpty(),"ARCHIVE_COORDINATOR_HISTORY_TERMINAL");
                collect(older,ids);
                require(ids.size()==3&&ids.contains("demo-message-archive-first")
                        &&ids.contains("demo-message-archive-reply")&&ids.contains("demo-message-archive-next"),
                        "ARCHIVE_COORDINATOR_HISTORY_COMPLETE");
            });
            await(instrumentation,callback->coordinator.openLocalRoom(ROOM,PEER,callback),"");
            main(instrumentation,()->{
                JSONArray messages=coordinator.messagesForRoom(ROOM);
                require(messages.length()==3,"ARCHIVE_DEFAULT_HISTORY_PAGE");
                for(int index=0;index<messages.length();index++){
                    JSONObject message=messages.getJSONObject(index);String id=message.getString("id");
                    boolean reply="demo-message-archive-reply".equals(id);
                    String expected=reply?"received_locally":"demo-message-archive-first".equals(id)?"server_accepted":"pending";
                    require(message.getBoolean("own")!=reply&&expected.equals(message.getString("status")),
                            "ARCHIVE_LOCAL_STATUS_PRESERVED");
                }
            });
            AtomicBoolean staged=new AtomicBoolean();
            await(instrumentation,coordinator::enroll,"LOCAL_ARCHIVE_READ_ONLY");
            await(instrumentation,callback->coordinator.openRoom(ROOM,PEER,callback),"LOCAL_ARCHIVE_READ_ONLY");
            await(instrumentation,callback->coordinator.verifyPeer(null,callback),"LOCAL_ARCHIVE_READ_ONLY");
            await(instrumentation,callback->coordinator.replacePeerIdentity(null,callback),"LOCAL_ARCHIVE_READ_ONLY");
            await(instrumentation,callback->coordinator.sendText(ROOM,"synthetic rejected draft",callback),"LOCAL_ARCHIVE_READ_ONLY");
            await(instrumentation,callback->coordinator.sendText(ROOM,"demo-message-archive-rejected",
                    "synthetic rejected draft",()->staged.set(true),callback),"LOCAL_ARCHIVE_READ_ONLY");
            await(instrumentation,callback->coordinator.retryPending(ROOM,callback),"LOCAL_ARCHIVE_READ_ONLY");
            await(instrumentation,callback->coordinator.poll(ROOM,callback),"LOCAL_ARCHIVE_READ_ONLY");
            await(instrumentation,coordinator::maintainPrekeys,"LOCAL_ARCHIVE_READ_ONLY");
            require(!staged.get()&&sessionChanges.get()==0,"ARCHIVE_NO_STAGE_OR_ENROLLMENT");
            main(instrumentation,()->{
                assertOffline(controller.get());
                require(coordinator.isLocalArchive(ROOM)&&!coordinator.isReady(ROOM)
                        &&coordinator.messagesForRoom(ROOM).length()==3,"ARCHIVE_REJECTIONS_PRESERVE_VIEW");
                coordinator.leaveLocalArchive();
                require(!coordinator.isLocalArchive(ROOM)&&coordinator.currentRoom().isEmpty()
                        &&coordinator.messagesForRoom(ROOM).length()==0&&coordinator.localArchiveNextBefore().isEmpty()
                        &&!coordinator.isReady(ROOM),"ARCHIVE_LEAVE_CLEARS_VIEW");
                coordinator.bindSession(null);
            });
            require(Arrays.equals(snapshot,read(base)),"ARCHIVE_CIPHERTEXT_UNCHANGED");
            require(!new File(base.getPath()+".bak").exists()&&!new File(base.getPath()+".new").exists(),
                    "ARCHIVE_NO_WRITE_RECOVERY_RESIDUE");
        } finally {
            try {
                main(instrumentation,()->{
                    if(selected.get()!=null)selected.get().close();
                    if(api.get()!=null)api.get().close();
                });
            } finally {
                if(snapshot!=null)Arrays.fill(snapshot,(byte)0);
                // Clear only the proven-fresh exact synthetic namespace. Never scan/delete other profiles.
                if(ownsFixture){
                    store.clear();
                    KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
                    require(!keys.containsAlias(alias),"ARCHIVE_FIXTURE_KEY_REMOVED");
                    require(!base.exists()&&!new File(base.getPath()+".bak").exists()&&!new File(base.getPath()+".new").exists(),
                            "ARCHIVE_FIXTURE_CIPHERTEXT_REMOVED");
                    require(!lock.exists()||lock.delete(),"ARCHIVE_FIXTURE_LOCK_REMOVED");
                }
                require(directory.delete(),"ARCHIVE_FIXTURE_DIRECTORY_REMOVED");
            }
        }
    }
    private static byte[] syntheticState(String account)throws Exception {
        Memory own=new Memory(),other=new Memory();
        try {
            SignalClient local=new SignalClient(own),peer=new SignalClient(other);
            local.initialize(ORIGIN,account,DEVICE,2);peer.initialize(ORIGIN,PEER,PEER_DEVICE,2);
            local.acceptPeer(ROOM,PEER,PEER_DEVICE,peer.protocolDeviceId(),peer.identityKeyBase64());
            local.acceptPeer(EMPTY_ROOM,PEER,PEER_DEVICE,peer.protocolDeviceId(),peer.identityKeyBase64());
            peer.acceptPeer(ROOM,account,DEVICE,local.protocolDeviceId(),local.identityKeyBase64());
            local.acceptBundle(ROOM,PEER,PEER_DEVICE,peer.publication().bundle(0));
            peer.receive(local.stageText(ROOM,PEER,PEER_DEVICE,"demo-message-archive-first","synthetic archive first"));
            local.markRelayAccepted("demo-message-archive-first");
            local.receive(peer.stageText(ROOM,account,DEVICE,"demo-message-archive-reply","synthetic archive reply"));
            local.stageText(ROOM,PEER,PEER_DEVICE,"demo-message-archive-next","synthetic archive next");
            return own.load();
        } finally {own.clear();other.clear();}
    }
    private static JSONObject list(Instrumentation instrumentation,MessageCoordinator coordinator,String peer,String after,int limit)throws Exception {
        Completion completion=new Completion();AtomicReference<JSONObject> result=new AtomicReference<>();
        main(instrumentation,()->coordinator.listLocalRooms(peer,after,limit,(page,error)->{result.set(page);completion.complete(error);}));
        completion.await("");require(result.get()!=null,"ARCHIVE_ROOM_PAGE_REQUIRED");return result.get();
    }
    private static void await(Instrumentation instrumentation,AsyncAction action,String expected)throws Exception {
        Completion completion=new Completion();main(instrumentation,()->action.run(completion));completion.await(expected);
    }
    private static void main(Instrumentation instrumentation,MainAction action)throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});
        Throwable error=failure.get();if(error instanceof Exception)throw (Exception)error;
        if(error instanceof Error)throw (Error)error;
        require(error==null,"ARCHIVE_MAIN_ACTION_FAILED");
    }
    private static void assertOffline(EmbeddedTorController controller){
        require(controller.nativeStopState()==EmbeddedTorController.NativeStopState.NOT_STARTED
                &&!controller.canStart()&&controller.lease()==null,"ARCHIVE_TOR_NEVER_STARTED");
    }
    private static void collect(JSONArray messages,Set<String> ids)throws Exception {
        for(int index=0;index<messages.length();index++)require(ids.add(messages.getJSONObject(index).getString("id")),
                "ARCHIVE_COORDINATOR_NO_DUPLICATE_PAGES");
    }
    private static String token(String prefix){return prefix+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);}
    private static byte[] read(File file)throws IOException {
        try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){
                if(output.size()+count>KeystoreBlobStore.MAX_SIGNAL_BYTES+EncryptedBlobEnvelope.OVERHEAD_BYTES)
                    throw new IOException("ARCHIVE_FIXTURE_FILE_LIMIT");
                output.write(buffer,0,count);
            }
            return output.toByteArray();
        }
    }
    private static void require(boolean condition,String code){TorTalkRuntimeInstrumentation.require(condition,code);}
}
