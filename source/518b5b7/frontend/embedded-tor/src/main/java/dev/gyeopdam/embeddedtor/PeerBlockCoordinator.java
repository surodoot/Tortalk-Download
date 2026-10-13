package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Native, actor-owned block operations. Disk intent precedes HTTP; only server state confirms success. */
final class PeerBlockCoordinator {
    interface Listener {void changed();}
    interface Callback {void complete(String code);}
    interface StatusCallback {void complete(PeerBlockState state,String code);}
    interface PageCallback {void complete(PeerBlockState.Page page,String code);}
    interface Reply {void complete(int status,JSONObject body,String code);}
    interface Api {void request(String method,String path,JSONObject body,AppSessionStore.Session owner,Reply reply);}
    interface Journal {
        List<PeerBlockJournal.Pending> load()throws IOException;
        List<PeerBlockJournal.Status> loadStatuses()throws IOException;
        void confirm(PeerBlockJournal.Status status,String acknowledgedMutation)throws IOException;
        void confirmAll(List<PeerBlockJournal.Status> statuses)throws IOException;
        void prepare(PeerBlockJournal.Pending pending)throws IOException;
        void acknowledge(String peer,String mutation)throws IOException;
        void resetAfterConfirmation()throws IOException;
    }
    interface JournalFactory {Journal open(String origin,String account)throws IOException;}
    private final JournalFactory factory;private final Api api;private final Executor disk,main;private final Listener listener;
    private AppSessionStore.Session session;private Journal journal;
    private long profileGeneration,operationGeneration;
    private boolean ready,failed,busy,closed;
    private List<PeerBlockJournal.Pending> pending=Collections.emptyList();
    private final LinkedHashMap<String,PeerBlockState> known=new LinkedHashMap<>();
    private final LinkedHashMap<String,PeerBlockJournal.Pending> acceptedHolds=new LinkedHashMap<>();

    /** Dependency boundary also permits deterministic queue/transport verification without Android IO. */
    PeerBlockCoordinator(JournalFactory factory,Api api,Executor disk,Executor main,Listener listener){this.factory=factory;this.api=api;this.disk=disk;this.main=main;this.listener=listener;}
    void bindSession(AppSessionStore.Session next){
        if(closed)return;
        if(session!=null&&next!=null&&session.serverOnion.equals(next.serverOnion)&&session.accountId.equals(next.accountId)&&session.sessionToken.equals(next.sessionToken)){session=next;return;}
        cancel();profileGeneration++;session=next;journal=null;ready=false;failed=false;pending=Collections.emptyList();known.clear();acceptedHolds.clear();listener.changed();
        if(next==null)return;long profile=profileGeneration;
        disk.execute(()->{try{Journal opened=factory.open(next.serverOnion,next.accountId);List<PeerBlockJournal.Pending> loaded=opened.load();List<PeerBlockJournal.Status> statuses=opened.loadStatuses();main.execute(()->{
            if(!profileCurrent(profile))return;journal=opened;install(loaded,statuses);ready=true;failed=false;listener.changed();
        });}catch(Exception failure){main.execute(()->{if(profileCurrent(profile)){failed=true;ready=false;listener.changed();}});}});
    }
    boolean ready(){return ready&&!failed&&session!=null&&!closed;}
    boolean failed(){return failed;}
    List<PeerBlockJournal.Pending> pending(){LinkedHashMap<String,PeerBlockJournal.Pending> all=new LinkedHashMap<>();for(PeerBlockJournal.Pending value:pending)all.put(value.peerId,value);all.putAll(acceptedHolds);return copy(new ArrayList<>(all.values()));}
    PeerBlockJournal.Pending pendingFor(String peer){PeerBlockJournal.Pending held=acceptedHolds.get(peer);if(held!=null)return held;for(PeerBlockJournal.Pending value:pending)if(value.peerId.equals(peer))return value;return null;}
    boolean blocksSending(String peer){PeerBlockJournal.Pending intent=pendingFor(peer);PeerBlockState state=known.get(peer);return !ready()||intent!=null&&intent.blocked||state!=null&&state.blocked;}
    PeerBlockState known(String peer){return known.get(peer);}
    void cancel(){operationGeneration++;busy=false;}
    void close(){closed=true;cancel();profileGeneration++;session=null;journal=null;pending=Collections.emptyList();known.clear();acceptedHolds.clear();}
    private boolean profileCurrent(long profile){return !closed&&session!=null&&profile==profileGeneration;}
    private boolean current(long profile,long operation){return profileCurrent(profile)&&busy&&operation==operationGeneration;}
    private long begin(Callback callback){if(closed||session==null){callback.complete("AUTH_REQUIRED");return -1;}if(busy){callback.complete("BUSY");return -1;}busy=true;return ++operationGeneration;}
    private void finish(long profile,long operation,Callback callback,String code){if(!current(profile,operation))return;busy=false;listener.changed();callback.complete(code);}
    private boolean validPeer(String peer){return session!=null&&PeerBlockState.validId(peer)&&!peer.equals(session.accountId);}
    private boolean remember(PeerBlockState value){
        PeerBlockState old=known.get(value.peerId);
        if(old!=null&&(old.revision>value.revision||old.revision==value.revision&&(!old.mutationId.equals(value.mutationId)||old.blocked!=value.blocked||old.legacyHold!=value.legacyHold)))return false;
        return true;
    }
    void fetch(String peer,StatusCallback callback){
        if(!validPeer(peer)){callback.complete(null,"INVALID_PEER");return;}long operation=begin(code->callback.complete(null,code));if(operation<0)return;long profile=profileGeneration;AppSessionStore.Session owner=session;
        api.request("GET","/v1/blocks/"+peer,null,owner,(status,body,code)->{
            if(!current(profile,operation))return;if(status!=200||!code.isEmpty()){finish(profile,operation,c->callback.complete(null,c),error(status,code));return;}
            final PeerBlockState state;try{state=PeerBlockState.parse(body,peer);if(!remember(state))throw new IllegalArgumentException();}catch(IllegalArgumentException malformed){finish(profile,operation,c->callback.complete(null,c),"INVALID_BLOCK_STATE");return;}
            PeerBlockJournal.Pending intent=pendingFor(peer);
            if(!ready()){finish(profile,operation,c->callback.complete(state,c),"");return;}
            persistStatus(profile,operation,intent!=null&&state.acknowledges(intent.mutationId,intent.expectedRevision,intent.blocked)?intent:null,state,callback);
        });
    }
    void page(String after,PageCallback callback){
        if(after==null||!after.isEmpty()&&!PeerBlockState.validId(after)){callback.complete(null,"INVALID_CURSOR");return;}long operation=begin(code->callback.complete(null,code));if(operation<0)return;long profile=profileGeneration;AppSessionStore.Session owner=session;
        api.request(after.isEmpty()?"GET":"POST",after.isEmpty()?"/v1/blocks":"/v1/blocks/page",after.isEmpty()?null:object("after_peer_id",after),owner,(status,body,code)->{
            if(!current(profile,operation))return;if(status!=200||!code.isEmpty()){finish(profile,operation,c->callback.complete(null,c),error(status,code));return;}
            try{PeerBlockState.Page result=PeerBlockState.page(body,after,owner.accountId);for(PeerBlockState value:result.items)if(!remember(value))throw new IllegalArgumentException();
                if(!ready()){finish(profile,operation,c->callback.complete(result,c),"");return;}Journal store=journal;
                disk.execute(()->{try{List<PeerBlockJournal.Status> updates=new ArrayList<>();for(PeerBlockState value:result.items)updates.add(record(value));store.confirmAll(updates);List<PeerBlockJournal.Pending> loaded=store.load();List<PeerBlockJournal.Status> statuses=store.loadStatuses();main.execute(()->{if(!profileCurrent(profile))return;install(loaded,statuses);listener.changed();finish(profile,operation,c->callback.complete(result,c),"");});}catch(Exception failure){journalFailure(profile,operation,c->callback.complete(null,c),store,failure);}});
            }
            catch(IllegalArgumentException malformed){finish(profile,operation,c->callback.complete(null,c),"INVALID_BLOCK_STATE");}
        });
    }
    void submit(PeerBlockState previous,boolean desired,StatusCallback callback){
        if(previous==null||!validPeer(previous.peerId)||!ready()||previous.revision==Integer.MAX_VALUE){callback.complete(null,"BLOCK_STORAGE_OR_STATE_UNAVAILABLE");return;}
        if(pendingFor(previous.peerId)!=null){callback.complete(null,"PENDING_BLOCK_EXISTS");return;}if(pending().size()>=8){callback.complete(null,"PENDING_BLOCK_CAPACITY");return;}
        PeerBlockJournal.Pending intent=new PeerBlockJournal.Pending(previous.peerId,"demo-block-"+UUID.randomUUID().toString().replace("-",""),previous.revision,desired);
        persistAndSend(intent,callback);
    }
    void retry(PeerBlockJournal.Pending intent,StatusCallback callback){
        PeerBlockJournal.Pending saved=intent==null?null:pendingFor(intent.peerId);
        if(!ready()||saved==null||!same(saved,intent)){callback.complete(null,"PENDING_BLOCK_CHANGED");return;}PeerBlockState observed=known.get(saved.peerId);if(observed!=null&&!observed.retryable(saved.mutationId,saved.expectedRevision)){callback.complete(null,"BLOCK_STATE_CHANGED");return;}persistAndSend(saved,callback);
    }
    private void persistAndSend(PeerBlockJournal.Pending intent,StatusCallback callback){
        long operation=begin(code->callback.complete(null,code));if(operation<0)return;long profile=profileGeneration;Journal store=journal;AppSessionStore.Session owner=session;
        acceptedHolds.put(intent.peerId,intent);listener.changed();
        disk.execute(()->{try{store.prepare(intent);List<PeerBlockJournal.Pending> loaded=store.load();List<PeerBlockJournal.Status> statuses=store.loadStatuses();main.execute(()->{
            if(!profileCurrent(profile))return;install(loaded,statuses);clearHold(intent);ready=true;failed=false;listener.changed();
            // Reconcile committed local intent even when navigation cancelled the network operation.
            if(!current(profile,operation))return;
            JSONObject body=new JSONObject();try{body.put("peer_id",intent.peerId).put("mutation_id",intent.mutationId).put("expected_revision",intent.expectedRevision);}catch(Exception impossible){finish(profile,operation,c->callback.complete(null,c),"INVALID_BLOCK_REQUEST");return;}
            api.request("POST",intent.blocked?"/v1/blocks":"/v1/blocks/unblock",body,owner,(status,response,code)->{
                if(!current(profile,operation))return;if(status!=200||!code.isEmpty()){finish(profile,operation,c->callback.complete(null,c),error(status,code));return;}
                final PeerBlockState accepted;try{accepted=PeerBlockState.parse(response,intent.peerId);if(!accepted.acknowledges(intent.mutationId,intent.expectedRevision,intent.blocked)||!remember(accepted))throw new IllegalArgumentException();}
                catch(IllegalArgumentException malformed){finish(profile,operation,c->callback.complete(null,c),"INVALID_BLOCK_ACK");return;}
                persistStatus(profile,operation,intent,accepted,callback);
            });
        });}catch(Exception failure){journalFailure(profile,operation,code->callback.complete(null,code),store,failure,intent);}});
    }
    private void persistStatus(long profile,long operation,PeerBlockJournal.Pending intent,PeerBlockState state,StatusCallback callback){
        Journal store=journal;disk.execute(()->{try{store.confirm(record(state),intent==null?"":intent.mutationId);List<PeerBlockJournal.Pending> loaded=store.load();List<PeerBlockJournal.Status> statuses=store.loadStatuses();main.execute(()->{
            if(!profileCurrent(profile))return;install(loaded,statuses);ready=true;failed=false;listener.changed();finish(profile,operation,code->callback.complete(state,code),"");
        });}catch(Exception failure){journalFailure(profile,operation,code->callback.complete(state,code),store,failure);}});
    }
    /** Explicit local abandonment only. It cannot cancel a request the server may already have applied. */
    void forgetAfterConfirmation(PeerBlockJournal.Pending intent,Callback callback){
        if(!ready()||intent==null){callback.complete("BLOCK_STORAGE_OR_STATE_UNAVAILABLE");return;}long operation=begin(callback);if(operation<0)return;long profile=profileGeneration;Journal store=journal;
        disk.execute(()->{try{store.acknowledge(intent.peerId,intent.mutationId);List<PeerBlockJournal.Pending> loaded=store.load();List<PeerBlockJournal.Status> statuses=store.loadStatuses();main.execute(()->{
            if(!profileCurrent(profile))return;install(loaded,statuses);clearHold(intent);listener.changed();finish(profile,operation,callback,"");
        });}catch(Exception failure){journalFailure(profile,operation,callback,store,failure);}});
    }
    void resetAfterConfirmation(Callback callback){
        if(session==null||!failed){callback.complete("BLOCK_STORAGE_OR_STATE_UNAVAILABLE");return;}long operation=begin(callback);if(operation<0)return;long profile=profileGeneration;AppSessionStore.Session owner=session;
        disk.execute(()->{try{Journal opened=factory.open(owner.serverOnion,owner.accountId);opened.resetAfterConfirmation();List<PeerBlockJournal.Pending> loaded=opened.load();main.execute(()->{
            if(!profileCurrent(profile))return;journal=opened;pending=copy(loaded);known.clear();acceptedHolds.clear();ready=true;failed=false;listener.changed();finish(profile,operation,callback,"");
        });}catch(Exception failure){storageFailure(profile,operation,callback);}});
    }
    private void install(List<PeerBlockJournal.Pending> requests,List<PeerBlockJournal.Status> statuses){
        pending=copy(requests);for(PeerBlockJournal.Status value:statuses){PeerBlockState state=state(value);PeerBlockState old=known.get(state.peerId);if(old==null||state.revision>old.revision||state.revision==old.revision&&state.blocked==old.blocked&&state.mutationId.equals(old.mutationId)&&state.legacyHold==old.legacyHold)known.put(state.peerId,state);}
    }
    private static PeerBlockJournal.Status record(PeerBlockState value){return new PeerBlockJournal.Status(value.peerId,value.blocked,value.revision,value.mutationId,value.legacyHold);}
    private static PeerBlockState state(PeerBlockJournal.Status value){try{return PeerBlockState.parse(new JSONObject().put("peer_id",value.peerId).put("blocked",value.blocked).put("revision",value.revision).put("mutation_id",value.mutationId).put("legacy_hold",value.legacyHold),value.peerId);}catch(Exception malformed){throw new IllegalArgumentException("INVALID_LOCAL_BLOCK_STATE");}}
    private void clearHold(PeerBlockJournal.Pending intent){PeerBlockJournal.Pending held=acceptedHolds.get(intent.peerId);if(held!=null&&held.mutationId.equals(intent.mutationId))acceptedHolds.remove(intent.peerId);}
    private void journalFailure(long profile,long operation,Callback callback,Journal store,Exception failure){journalFailure(profile,operation,callback,store,failure,null);}
    private void journalFailure(long profile,long operation,Callback callback,Journal store,Exception failure,PeerBlockJournal.Pending accepted){
        if(failure instanceof PeerBlockJournal.JournalException){PeerBlockJournal.Code code=((PeerBlockJournal.JournalException)failure).code;
            if(code==PeerBlockJournal.Code.STATE_CHANGED||code==PeerBlockJournal.Code.STALE_ACK||code==PeerBlockJournal.Code.PENDING_CONFLICT||code==PeerBlockJournal.Code.CAPACITY_REACHED){
                try{List<PeerBlockJournal.Pending> loaded=store.load();List<PeerBlockJournal.Status> statuses=store.loadStatuses();main.execute(()->{if(!profileCurrent(profile))return;install(loaded,statuses);if(accepted!=null)clearHold(accepted);ready=true;failed=false;listener.changed();finish(profile,operation,callback,"BLOCK_STATE_CHANGED");});return;}catch(Exception unreadable){/* Preserve failure fence below. */}
            }
        }
        storageFailure(profile,operation,callback);
    }
    private void storageFailure(long profile,long operation,Callback callback){main.execute(()->{if(!profileCurrent(profile))return;failed=true;ready=false;listener.changed();finish(profile,operation,callback,"BLOCK_STORAGE_FAILED");});}
    private static List<PeerBlockJournal.Pending> copy(List<PeerBlockJournal.Pending> values){return Collections.unmodifiableList(new ArrayList<>(values));}
    private static boolean same(PeerBlockJournal.Pending a,PeerBlockJournal.Pending b){return a.peerId.equals(b.peerId)&&a.mutationId.equals(b.mutationId)&&a.expectedRevision==b.expectedRevision&&a.blocked==b.blocked;}
    private static String error(int status,String code){return status==401?"AUTH_REQUIRED":status==409?"BLOCK_STATE_CHANGED":status==429?"RATE_LIMITED":code.equals("CANCELLED")?"CANCELLED":"BLOCK_REQUEST_UNCONFIRMED";}
    private static JSONObject object(String key,String value){JSONObject result=new JSONObject();try{return result.put(key,value);}catch(Exception impossible){throw new IllegalArgumentException("INVALID_BLOCK_REQUEST");}}
}
