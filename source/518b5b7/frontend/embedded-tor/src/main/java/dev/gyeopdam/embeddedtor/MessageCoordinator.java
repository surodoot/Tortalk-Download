package dev.gyeopdam.embeddedtor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.*;
import java.util.Base64;
import org.json.*;

/** Native-only orchestration. Network receipts never imply delivered/read, and pins require explicit UI consent. */
final class MessageCoordinator {
    interface Callback { void complete(String errorCode); }
    interface ContactCallback { void complete(JSONObject contact,String errorCode); }
    interface LocalRoomsCallback { void complete(JSONObject page,String errorCode); }
    interface Listener { void sessionChanged(AppSessionStore.Session session); void changed(); }
    static final class Peer {
        final String roomId,accountId,deviceId,identityKeyBase64,fingerprint,oldIdentityKeyBase64,oldFingerprint;
        final int protocolDeviceId;
        final SignalClient.Bundle bundle;
        Peer(String room,String account,String device,String identity,int protocol,String fingerprint,SignalClient.Bundle bundle) {
            this(room,account,device,identity,protocol,fingerprint,bundle,"","");
        }
        Peer(String room,String account,String device,String identity,int protocol,String fingerprint,SignalClient.Bundle bundle,String oldIdentity,String oldFingerprint) {
            roomId=room;accountId=account;deviceId=device;identityKeyBase64=identity;protocolDeviceId=protocol;this.fingerprint=fingerprint;this.bundle=bundle;
            oldIdentityKeyBase64=oldIdentity;this.oldFingerprint=oldFingerprint;
        }
    }
    private static final ExecutorService CRYPTO_WORKER=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"signal-state");t.setDaemon(true);return t;});
    private static final class Shared {
        final KeystoreBlobStore blob;
        final SignalClient signal;
        final ExecutorService worker=CRYPTO_WORKER;
        Shared(Context context,String namespace) {
            blob=new KeystoreBlobStore(context,namespace);
            signal=new SignalClient(new SignalClient.StateStorage(){public byte[] load() throws java.io.IOException{return blob.read();}public void save(byte[] bytes)throws java.io.IOException{blob.write(bytes);}
                public void compareAndSave(byte[] expected,byte[] next)throws java.io.IOException{blob.write(next,previous->{if(!Arrays.equals(expected,previous))throw new KeystoreBlobStore.StorageException(KeystoreBlobStore.Code.IDENTITY_MISMATCH);});}});
        }
    }
    private static final Map<String,Shared> profiles=new HashMap<>();
    private static synchronized Shared shared(Context c,AppSessionStore.Session session){
        String key=SignalProfile.namespace(session.serverOnion,session.accountId);
        Shared value=profiles.get(key);if(value==null){value=new Shared(c.getApplicationContext(),key);profiles.put(key,value);}return value;
    }
    private final Context context;
    private Shared core;
    private final TorApiClient api;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile AppSessionStore.Session session;
    private volatile long generation;
    private boolean busy,enrolled;
    private volatile boolean closed;
    private Peer peer;
    private boolean peerReady;
    private boolean localArchive;
    private Shared archiveCore;
    private String archiveOrigin="",archiveAccount="",archiveRoom="",archivePeer="",archiveNextBefore="";
    private JSONArray localMessages=new JSONArray();
    MessageCoordinator(Context context,TorApiClient api,Listener listener){this.context=context.getApplicationContext();this.api=api;this.listener=listener;}
    void bindSession(AppSessionStore.Session value){requireMain();cancel();resetArchive();session=value;core=value==null?null:shared(context,value);enrolled=false;peer=null;peerReady=false;localMessages=new JSONArray();listener.changed();}
    Peer pendingPeer(){return peer;}
    boolean isReady(String room){return !localArchive&&enrolled&&peerReady&&peer!=null&&peer.roomId.equals(room)&&session!=null;}
    String currentRoom(){return localArchive?archiveProfileCurrent()?archiveRoom:"":peer==null?"":peer.roomId;}
    JSONArray messagesForRoom(String room){return room!=null&&room.equals(currentRoom())?messages():new JSONArray();}
    JSONArray messages(){if(localArchive&&!archiveProfileCurrent())return new JSONArray();try{return new JSONArray(localMessages.toString());}catch(JSONException impossible){return new JSONArray();}}
    boolean isLocalArchive(String room){return localArchive&&archiveProfileCurrent()&&!archiveRoom.isEmpty()&&archiveRoom.equals(room);}
    String localArchiveNextBefore(){return localArchive&&archiveProfileCurrent()?archiveNextBefore:"";}
    void leaveLocalArchive(){requireMain();if(!localArchive)return;cancel();resetArchive();peer=null;peerReady=false;localMessages=new JSONArray();listener.changed();}
    private void resetArchive(){localArchive=false;archiveCore=null;archiveOrigin="";archiveAccount="";archiveRoom="";archivePeer="";archiveNextBefore="";}
    private boolean archiveProfileCurrent(){AppSessionStore.Session value=session;return !closed&&value!=null&&core==archiveCore&&archiveOrigin.equals(value.serverOnion)&&archiveAccount.equals(value.accountId)&&archiveOrigin.equals(api.configuredOnion());}
    private long begin(Callback done){requireMain();if(closed||session==null){done.complete("LOGIN_REQUIRED");return -1;}if(busy){done.complete("BUSY");return -1;}if(!session.serverOnion.equals(api.configuredOnion())){done.complete("ORIGIN_MISMATCH");return -1;}busy=true;return ++generation;}
    private long beginLive(Callback done){requireMain();if(localArchive){done.complete("LOCAL_ARCHIVE_READ_ONLY");return -1;}return begin(done);}
    private boolean current(long g){return !closed&&session!=null&&g==generation;}
    private void finish(long g,Callback done,String error){if(!current(g))return;busy=false;listener.changed();done.complete(error);}
    private interface Work<T>{T run(Shared core)throws Exception;}
    private interface Value<T>{void accept(T result);}
    private <T> void crypto(long g,Callback done,Work<T> task,Value<T> success){
        final Shared selected=core;if(selected==null){finish(g,done,"LOGIN_REQUIRED");return;}
        selected.worker.execute(()->{if(!current(g))return;try{T value=task.run(selected);main.post(()->{if(current(g))success.accept(value);});}catch(Exception|LinkageError failure){main.post(()->finish(g,done,"CRYPTO_OR_STORAGE_FAILED"));}});
    }
    private void request(long g,String method,String path,JSONObject body,TorApiClient.Callback callback){
        AppSessionStore.Session value=session;if(!current(g)||value==null)return;
        if(localArchive){callback.complete(0,new JSONObject(),"LOCAL_ARCHIVE_READ_ONLY");return;}
        api.request(method,path,body,value.sessionToken,value.deviceToken,value.serverOnion,(status,result,error)->{if(current(g))callback.complete(status,result,error);});
    }
    private static boolean ok(int status,String error){return error.isEmpty()&&status>=200&&status<300;}
    private static String error(int status,String code){return !code.isEmpty()?code:status==401?"AUTH_REQUIRED":status==409?"CONFLICT_OR_PREKEYS_EXHAUSTED":status==410?"SEND_WINDOW_CLOSED":"SERVER_REJECTED";}
    private static JSONObject object(String... pairs){JSONObject o=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)o.put(pairs[i],pairs[i+1]);}catch(JSONException impossible){throw new IllegalStateException();}return o;}

    private boolean localOperationCurrent(long g,Shared selected,AppSessionStore.Session identity){
        AppSessionStore.Session value=session;
        return current(g)&&core==selected&&value!=null&&identity.serverOnion.equals(value.serverOnion)
                &&identity.accountId.equals(value.accountId)&&identity.serverOnion.equals(api.configuredOnion());
    }
    private <T> void localRead(long g,AppSessionStore.Session identity,Callback done,Work<T> task,Value<T> success){
        final Shared selected=core;if(selected==null){finish(g,done,"LOGIN_REQUIRED");return;}
        selected.worker.execute(()->{if(!localOperationCurrent(g,selected,identity))return;
            try{T value=task.run(selected);main.post(()->{if(localOperationCurrent(g,selected,identity))success.accept(value);});}
            catch(Exception|LinkageError failure){String code=localReadError(failure);main.post(()->{if(localOperationCurrent(g,selected,identity))finish(g,done,code);});}
        });
    }
    private static String localReadError(Throwable failure){
        if(failure instanceof IllegalArgumentException){String code=failure.getMessage();
            if("unknown_local_room".equals(code))return "LOCAL_ROOM_NOT_FOUND";
            if("archive_profile_mismatch".equals(code))return "LOCAL_PROFILE_MISMATCH";
            if("archive_room_conflict".equals(code))return "LOCAL_HISTORY_CONFLICT";
            if("archive_cursor".equals(code)||"archive_page_limit".equals(code)||"invalid_identifier".equals(code))return "INVALID_LOCAL_REQUEST";
        }
        return "CRYPTO_OR_STORAGE_FAILED";
    }
    /** Empty peer explicitly means all verified local rooms in this bound profile. */
    void listLocalRooms(String peerAccount,String afterRoomId,int limit,LocalRoomsCallback done){
        Callback failure=code->{if(!code.isEmpty())done.complete(new JSONObject(),code);};
        long g=begin(failure);if(g<0)return;final AppSessionStore.Session identity=session;
        localRead(g,identity,failure,selected->SignalJson.localRooms(selected.signal.listLocalRooms(identity.serverOnion,identity.accountId,peerAccount,afterRoomId,limit)),
                page->{busy=false;done.complete(page,"");});
    }
    void openLocalRoom(String room,String expectedPeer,Callback done){openLocalRoom(room,expectedPeer,"",200,done);}
    /** Selecting an archive revokes any live operation before reading; it never creates send readiness. */
    void openLocalRoom(String room,String expectedPeer,String beforeMessageCursor,int limit,Callback done){
        requireMain();
        if(closed||session==null){done.complete("LOGIN_REQUIRED");return;}
        if(!session.serverOnion.equals(api.configuredOnion())){done.complete("ORIGIN_MISMATCH");return;}
        if(!UiCommandPolicy.validOpaqueId(room)||!UiCommandPolicy.validOpaqueId(expectedPeer)||session.accountId.equals(expectedPeer)
                ||beforeMessageCursor==null||limit<1||limit>200){done.complete("INVALID_LOCAL_REQUEST");return;}
        cancel();localArchive=true;archiveCore=core;archiveOrigin=session.serverOnion;archiveAccount=session.accountId;
        archiveRoom="";archivePeer="";archiveNextBefore="";peer=null;peerReady=false;localMessages=new JSONArray();
        long g=begin(done);if(g<0)return;final AppSessionStore.Session identity=session;
        localRead(g,identity,done,selected->{
            SignalClient.LocalHistoryPage page=selected.signal.openLocalRoom(identity.serverOnion,identity.accountId,room,expectedPeer,beforeMessageCursor,limit);
            return new Object[]{page,SignalJson.localHistoryMessages(page,identity.accountId)};
        },result->{SignalClient.LocalHistoryPage page=(SignalClient.LocalHistoryPage)result[0];
            archiveRoom=page.room.roomId;archivePeer=page.room.peerAccountId;archiveNextBefore=page.nextBeforeMessageCursor;
            localMessages=(JSONArray)result[1];finish(g,done,"");
        });
    }

    void ownContact(ContactCallback done){
        Callback failure=code->{if(!code.isEmpty())done.complete(new JSONObject(),code);};
        long g=begin(failure);if(g<0)return;
        crypto(g,failure,core->new JSONObject().put("server",core.signal.origin()).put("account_id",core.signal.accountId())
                .put("device_id",core.signal.deviceId()).put("protocol_device_id",core.signal.protocolDeviceId())
                .put("identity_key_base64",core.signal.identityKeyBase64()).put("fingerprint",SignalJson.keyDigest(core.signal.identityKeyBase64())).put("registered",enrolled),
                contact->{busy=false;listener.changed();done.complete(contact,"");});
    }
    void enroll(Callback done){
        long g=beginLive(done);if(g<0)return;final AppSessionStore.Session identity=session;
        request(g,"GET","/v1/devices",null,(ownedStatus,ownedBody,ownedError)->{
            if(!ok(ownedStatus,ownedError)){finish(g,done,error(ownedStatus,ownedError));return;}
            final Set<Integer> excluded=new HashSet<>();
            try{JSONArray owned=ownedBody.getJSONArray("devices");if(owned.length()>4)throw new IllegalArgumentException();
                for(int i=0;i<owned.length();i++){JSONObject device=owned.getJSONObject(i);if(!identity.accountId.equals(device.getString("account_id")))throw new IllegalArgumentException();
                    Object raw=device.get("protocol_device_id");if(!(raw instanceof Number))throw new IllegalArgumentException();double n=((Number)raw).doubleValue();int id=((Number)raw).intValue();
                    if(!Double.isFinite(n)||n!=id||id<0||id>127)throw new IllegalArgumentException();if(id>0)excluded.add(id);
                }
            }catch(Exception invalid){finish(g,done,"INVALID_DEVICE_HISTORY");return;}
        crypto(g,done,core->{
            byte[] existing=core.blob.read();boolean empty=existing==null;if(existing!=null)Arrays.fill(existing,(byte)0);
            String device=empty?"demo-device-"+UUID.randomUUID():core.signal.deviceId();
            core.signal.prepareIdentity(identity.serverOnion,device,20,excluded);core.signal.bindAccount(identity.accountId);
            String token=core.signal.prepareDeviceToken();
            return object("device_id",device,"public_key_base64",core.signal.devicePublicKeyBase64(),"device_token",token);
        },registration->{
            // The credential already exists durably in the encrypted Signal state before HTTP.
            request(g,"POST","/v1/devices",registration,(status,body,code)->{
                if(!ok(status,code)){finish(g,done,error(status,code));return;}
                try{
                    JSONObject device=body.getJSONObject("device");
                    if(!identity.accountId.equals(device.getString("account_id"))||!registration.getString("device_id").equals(device.getString("device_id"))
                            ||!registration.getString("public_key_base64").equals(device.getString("public_key_base64"))
                            ||!registration.getString("device_token").equals(body.getString("device_token")))throw new IllegalArgumentException();
                    session=new AppSessionStore.Session(identity.serverOnion,identity.accountId,identity.accountToken,device.getString("device_id"),body.getString("device_token"),identity.sessionToken);
                    listener.sessionChanged(session);
                }catch(Exception invalid){finish(g,done,"INVALID_DEVICE_BINDING");return;}
                crypto(g,done,core->SignalJson.publication(core.signal.publication()),publication->{
                    request(g,"POST","/v1/devices/"+session.deviceId+"/prekeys",publication,(s,b,c)->{
                        if(!ok(s,c)){finish(g,done,error(s,c));return;}
                        if(!session.deviceId.equals(b.optString("device_id"))){finish(g,done,"INVALID_DEVICE_BINDING");return;}
                        enrolled=true;finish(g,done,"");
                    });
                });
            });
        });
        });
    }
    void openRoom(String room,Callback done){requireMain();done.complete(localArchive?"LOCAL_ARCHIVE_READ_ONLY":"EXPECTED_PEER_REQUIRED");}
    void openRoom(String room,String expectedPeer,Callback done){
        long g=beginLive(done);if(g<0)return;if(!enrolled||!UiCommandPolicy.validOpaqueId(room)||!UiCommandPolicy.validOpaqueId(expectedPeer)||session.accountId.equals(expectedPeer)){finish(g,done,"DEVICE_NOT_READY");return;}
        peer=null;peerReady=false;localMessages=new JSONArray();
        request(g,"GET","/v1/rooms/"+room+"/devices",null,(status,body,code)->{
            if(!ok(status,code)){finish(g,done,error(status,code));return;}
            final Peer candidate;
            try{
                JSONArray devices=body.getJSONArray("devices");if(devices.length()>4)throw new IllegalArgumentException();JSONObject selected=null;
                for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i);if(!session.accountId.equals(d.getString("account_id"))){if(!expectedPeer.equals(d.getString("account_id")))throw new IllegalArgumentException();if(selected!=null)throw new IllegalArgumentException();selected=d;}}
                if(selected==null){finish(g,done,"PEER_DEVICE_NOT_READY");return;}
                String account=selected.getString("account_id"),device=selected.getString("device_id"),identity=selected.getString("identity_key_base64");
                Object protocolValue=selected.get("protocol_device_id");if(!(protocolValue instanceof Number))throw new IllegalArgumentException();
                double protocolNumber=((Number)protocolValue).doubleValue();int protocol=((Number)protocolValue).intValue();if(!Double.isFinite(protocolNumber)||protocolNumber!=protocol)throw new IllegalArgumentException();
                if(!UiCommandPolicy.validOpaqueId(account)||!UiCommandPolicy.validOpaqueId(device)||protocol<1||protocol>127)throw new IllegalArgumentException();
                byte[] raw=Base64.getDecoder().decode(selected.getString("public_key_base64"));byte[] encoded=Base64.getDecoder().decode(identity);
                if(raw.length!=32||encoded.length!=33||encoded[0]!=5||!Arrays.equals(raw,Arrays.copyOfRange(encoded,1,33)))throw new IllegalArgumentException();
                String fingerprint=SignalJson.keyDigest(identity);if(!fingerprint.equals(selected.getString("key_digest")))throw new IllegalArgumentException();
                candidate=new Peer(room,account,device,identity,protocol,fingerprint,null);
            }catch(Exception invalid){finish(g,done,"INVALID_PEER_DEVICE");return;}
            crypto(g,done,core->{
                String old="";try{String pinned=core.signal.pinnedIdentityKeyBase64(candidate.accountId);old=pinned==null?"":pinned;}catch(IllegalArgumentException unverified){ }
                boolean trusted=candidate.identityKeyBase64.equals(old);
                if(trusted){try{core.signal.acceptPeer(room,candidate.accountId,candidate.deviceId,candidate.protocolDeviceId,candidate.identityKeyBase64);}catch(IllegalArgumentException changedBinding){trusted=false;}}
                Peer result=new Peer(room,candidate.accountId,candidate.deviceId,candidate.identityKeyBase64,candidate.protocolDeviceId,candidate.fingerprint,null,old,old.isEmpty()?"":SignalJson.keyDigest(old));
                return new Object[]{result,trusted};
            },result->{peer=(Peer)result[0];peerReady=(Boolean)result[1];refreshLocal(g,done,peerReady?"":"PEER_NOT_VERIFIED");});
        });
    }
    /** The UI must obtain out-of-band identity/fingerprint confirmation before invoking. */
    void verifyPeer(Peer approved,Callback done){
        long g=beginLive(done);if(g<0)return;if(approved==null||approved!=peer){finish(g,done,"PEER_CHANGED");return;}
        if(!approved.oldIdentityKeyBase64.isEmpty()&&!peerReady){finish(g,done,"PEER_REPLACEMENT_REQUIRED");return;}
        crypto(g,done,core->{core.signal.acceptPeer(approved.roomId,approved.accountId,approved.deviceId,approved.protocolDeviceId,approved.identityKeyBase64);
            if(approved.bundle!=null)core.signal.acceptBundle(approved.roomId,approved.accountId,approved.deviceId,approved.bundle);return true;
        },ignored->{peerReady=true;refreshLocal(g,done,"");});
    }
    void replacePeerIdentity(Peer approved,Callback done){
        long g=beginLive(done);if(g<0)return;if(approved==null||approved!=peer||approved.oldIdentityKeyBase64.isEmpty()){finish(g,done,"PEER_CHANGED");return;}
        crypto(g,done,core->{core.signal.replacePeerIdentity(approved.roomId,approved.accountId,approved.deviceId,approved.protocolDeviceId,approved.identityKeyBase64,approved.oldIdentityKeyBase64);return true;},
                ignored->{peerReady=true;refreshLocal(g,done,"");});
    }
    void sendText(String room,String text,Callback done){sendText(room,"demo-message-"+UUID.randomUUID(),text,()->{},done);}
    void sendText(String room,String messageId,String text,Runnable staged,Callback done){
        long g=beginLive(done);if(g<0)return;if(!isReady(room)){finish(g,done,"PEER_NOT_VERIFIED");return;}
        if(!UiCommandPolicy.validOpaqueId(messageId)||staged==null){finish(g,done,"INVALID_DRAFT");return;}
        final Peer target=peer;
        crypto(g,done,core->core.signal.hasSession(target.accountId,target.deviceId),exists->{
            if(exists){stageSend(g,target,messageId,text,staged,done);return;}
            crypto(g,done,core->core.signal.claimId(room,target.deviceId),claim->{
                final JSONObject q;try{q=SignalJson.claimRequest(claim,session.deviceId,target.deviceId);}catch(JSONException invalid){finish(g,done,"INVALID_REQUEST");return;}
                request(g,"POST","/v1/rooms/"+room+"/prekeys/claim",q,(status,body,code)->{
                    if(!ok(status,code)){finish(g,done,error(status,code));return;}
                    crypto(g,done,core->{SignalClient.Bundle bundle=SignalJson.claimedBundle(body,claim,room,target.accountId,target.deviceId,target.identityKeyBase64);
                        core.signal.acceptBundle(room,target.accountId,target.deviceId,bundle);return true;
                    },ignored->stageSend(g,target,messageId,text,staged,done));
                });
            });
        });
    }
    private void stageSend(long g,Peer target,String messageId,String text,Runnable staged,Callback done){
        crypto(g,done,core->core.signal.stageText(target.roomId,target.accountId,target.deviceId,messageId,text),envelope->{
            // The immutable native draft ID exists before this operation, so a canceled UI callback
            // can retry the same durable envelope instead of encrypting a duplicate message.
            staged.run();
            Callback queued=code->done.complete(code.isEmpty()||code.startsWith("QUEUED_")?code:"QUEUED_"+code);
            crypto(g,queued,core->readLocal(core,target),value->{localMessages=value;listener.changed();sendEnvelope(g,envelope,queued);});
        });
    }
    private void sendEnvelope(long g,SignalClient.Envelope envelope,Callback done){
        crypto(g,done,core->SignalJson.sendRequest(core.signal,envelope),payload->{
            request(g,"POST","/v1/rooms/"+envelope.roomId+"/messages",payload,(status,body,code)->{
                if(!ok(status,code)){refreshLocal(g,done,"QUEUED_"+error(status,code));return;}
                if(!envelope.messageId.equals(body.optString("message_id"))||!envelope.roomId.equals(body.optString("room_id"))||!envelope.senderAccountId.equals(body.optString("sender_id"))||!envelope.recipientAccountId.equals(body.optString("recipient_id"))||!"accepted".equals(body.optString("status"))){finish(g,done,"INVALID_ACCEPTANCE");return;}
                crypto(g,done,core->{core.signal.markRelayAccepted(envelope.messageId);return true;},ignored->refreshLocal(g,done,""));
            });
        });
    }
    void maintainPrekeys(Callback done){
        long g=beginLive(done);if(g<0)return;if(!enrolled){finish(g,done,"DEVICE_NOT_READY");return;}
        request(g,"GET","/v1/devices/"+session.deviceId+"/prekeys",null,(status,body,code)->{
            if(!ok(status,code)){finish(g,done,error(status,code));return;}
            final int remaining;
            try{remaining=remainingPrekeys(body);}catch(Exception invalid){finish(g,done,"INVALID_PREKEY_STATUS");return;}
            crypto(g,done,core->{SignalClient.Replenishment pending=core.signal.pendingReplenishment();
                return pending!=null?pending:remaining<=8?core.signal.prepareReplenishment(20-remaining):null;
            },batch->{
                if(batch==null){finish(g,done,"");return;}
                final JSONObject request;try{request=SignalJson.replenishment(batch);}catch(JSONException invalid){finish(g,done,"INVALID_REQUEST");return;}
                request(g,"POST","/v1/devices/"+session.deviceId+"/prekeys/replenish",request,(s,b,c)->{
                    if(!ok(s,c)){finish(g,done,error(s,c));return;}
                    try{remainingPrekeys(b);}catch(Exception invalid){finish(g,done,"INVALID_PREKEY_STATUS");return;}
                    crypto(g,done,core->{core.signal.confirmReplenishment(batch.batchId);return true;},ignored->finish(g,done,""));
                });
            });
        });
    }
    private int remainingPrekeys(JSONObject body)throws JSONException{
        if(!session.deviceId.equals(body.getString("device_id")))throw new IllegalArgumentException();
        Object raw=body.get("remaining_pairs");if(!(raw instanceof Number))throw new IllegalArgumentException();
        double value=((Number)raw).doubleValue();int result=((Number)raw).intValue();
        if(!Double.isFinite(value)||value!=result||result<0||result>20)throw new IllegalArgumentException();return result;
    }
    void retryPending(String room,Callback done){
        long g=beginLive(done);if(g<0)return;if(!isReady(room)){finish(g,done,"PEER_NOT_VERIFIED");return;}
        crypto(g,done,core->core.signal.pendingOutbox(),pending->{
            for(SignalClient.Envelope e:pending)if(e.roomId.equals(room)&&e.recipientAccountId.equals(peer.accountId)&&e.recipientDeviceId.equals(peer.deviceId)){sendEnvelope(g,e,done);return;}
            finish(g,done,"");
        });
    }
    void poll(String room,Callback done){
        long g=beginLive(done);if(g<0)return;if(!isReady(room)){finish(g,done,"PEER_NOT_VERIFIED");return;}
        request(g,"GET","/v1/rooms/"+room+"/devices/"+session.deviceId+"/inbox",null,(status,body,code)->{
            if(!ok(status,code)){finish(g,done,error(status,code));return;}
            try{JSONArray receipts=body.getJSONArray("receipts");if(receipts.length()>64)throw new IllegalArgumentException();receiveNext(g,room,receipts,0,done);}
            catch(Exception invalid){finish(g,done,"INVALID_INBOX");}
        });
    }
    private void receiveNext(long g,String room,JSONArray receipts,int index,Callback done)throws JSONException{
        if(!current(g))return;if(index>=receipts.length()){refreshLocal(g,done,"");return;}
        JSONObject receipt=receipts.getJSONObject(index);String id=receipt.getString("message_id");
        if(!UiCommandPolicy.validOpaqueId(id)||!room.equals(receipt.getString("room_id"))||!session.accountId.equals(receipt.getString("recipient_id"))){finish(g,done,"INVALID_RECEIPT");return;}
        request(g,"GET","/v1/rooms/"+room+"/messages/"+id,null,(status,body,code)->{
            if(!ok(status,code)){finish(g,done,error(status,code));return;}
            crypto(g,done,core->{SignalClient.Envelope e=SignalJson.receivedEnvelope(core.signal,body,room,id);return core.signal.receive(e);},incoming->{
                // receive() commits ratchet + plaintext inbox atomically before any deletion ACK.
                request(g,"POST","/v1/rooms/"+room+"/messages/"+id+"/ack",new JSONObject(),(s,b,c)->{
                    if(!ok(s,c)){refreshLocal(g,done,error(s,c));return;}
                    if(!id.equals(b.optString("message_id"))||!session.accountId.equals(b.optString("recipient_id"))||!"recipient-acknowledged".equals(b.optString("status"))){finish(g,done,"INVALID_ACK");return;}
                    try{receiveNext(g,room,receipts,index+1,done);}catch(JSONException invalid){finish(g,done,"INVALID_INBOX");}
                });
            });
        });
    }
    private void refreshLocal(long g,Callback done,String outcome){
        final Peer selected=peer;
        crypto(g,done,core->readLocal(core,selected),value->{localMessages=value;finish(g,done,outcome);});
    }
    private static JSONArray readLocal(Shared core,Peer selected)throws Exception{
        JSONArray result=new JSONArray();if(selected==null)return result;
        ArrayList<SignalClient.Incoming> ordered=new ArrayList<>();
        for(SignalClient.Incoming in:core.signal.inbox())if(in.envelope.roomId.equals(selected.roomId))ordered.add(in);
        for(SignalClient.Incoming out:core.signal.sentMessages())if(out.envelope.roomId.equals(selected.roomId))ordered.add(out);
        Collections.sort(ordered,(a,b)->{int order=Long.compare(a.createdAtMillis,b.createdAtMillis);return order!=0?order:a.envelope.messageId.compareTo(b.envelope.messageId);});
        // The encrypted history remains intact; keep only a bounded view cache on the main thread.
        for(int i=Math.max(0,ordered.size()-200);i<ordered.size();i++){
            SignalClient.Incoming entry=ordered.get(i);boolean own=entry.envelope.senderAccountId.equals(core.signal.accountId());JSONObject value=view(entry,own);
            if(own&&!entry.relayAccepted&&(entry.retired||!entry.envelope.recipientDeviceId.equals(selected.deviceId)))value.put("status","retired_identity");
            result.put(value);
        }
        return result;
    }
    private static JSONObject view(SignalClient.Incoming value,boolean outgoing)throws JSONException{
        return new JSONObject().put("id",value.envelope.messageId).put("text",value.text).put("own",outgoing)
                .put("local_time",value.createdAtMillis).put("status",outgoing?(value.relayAccepted?"server_accepted":"pending"):"received_locally");
    }
    void cancel(){requireMain();generation++;busy=false;api.cancelAll();}
    void close(){requireMain();closed=true;cancel();resetArchive();session=null;peer=null;localMessages=new JSONArray();}
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("MAIN_THREAD_REQUIRED");}
}
