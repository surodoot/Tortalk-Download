package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Native-only exact recovery retry. No capability is projected to the packaged page or logged. */
final class DeviceRecoveryCoordinator {
    interface Store {
        PendingDeviceRecoveryStore.Pending load()throws IOException;
        PendingDeviceRecoveryStore.Pending prepare(String origin,String account,String target,String oldCapability)throws IOException;
        PendingDeviceRecoveryStore.Pending markConfirmed(String recoveryId)throws IOException;
        void acknowledge(String recoveryId)throws IOException;
        void resetAfterConfirmation(String expectedRecoveryId)throws IOException;
        long epoch();
        boolean dispatchIfCurrent(long expected,Runnable request);
    }
    interface Reply {void complete(int status,JSONObject body,String transportCode);}
    interface Api {void request(String method,String path,JSONObject body,AppSessionStore.Session owner,String origin,Reply reply);}
    interface Callback {void complete(String code);}
    interface Listener {void changed();void sessionAuthenticated(AppSessionStore.Session session);}
    private final Store store;private final Api api;private final Executor disk,main;private final Listener listener;
    private PendingDeviceRecoveryStore.Pending pending;
    private AppSessionStore.Session session;private String origin="";
    private long generation,pendingEpoch;private boolean busy,loaded,failed,closed,preparing;
    DeviceRecoveryCoordinator(Store store,Api api,Executor disk,Executor main,Listener listener){this.store=store;this.api=api;this.disk=disk;this.main=main;this.listener=listener;}
    void updateContext(String nextOrigin,AppSessionStore.Session next){
        if(closed)return;String value=nextOrigin==null?"":nextOrigin;
        boolean same=origin.equals(value)&&(session==null&&next==null||session!=null&&next!=null&&session.accountId.equals(next.accountId)&&session.serverOnion.equals(next.serverOnion)&&session.sessionToken.equals(next.sessionToken));
        if(!same)cancel();origin=value;session=next;
    }
    PendingDeviceRecoveryStore.Pending pending(){return pending;}
    boolean loaded(){return loaded;}
    boolean failed(){return failed;}
    boolean busy(){return busy||preparing;}
    boolean requiresAcknowledgement(){return pending!=null&&pending.confirmed&&session!=null&&pending.serverOnion.equals(origin)&&pending.serverOnion.equals(session.serverOnion)&&pending.accountId.equals(session.accountId);}
    void cancel(){generation++;busy=false;}
    void close(){closed=true;cancel();pending=null;session=null;origin="";}
    private boolean current(long operation){return !closed&&busy&&operation==generation;}
    private long begin(Callback callback){if(closed){callback.complete("RECOVERY_CLOSED");return -1;}if(busy||preparing){callback.complete("BUSY");return -1;}busy=true;return ++generation;}
    private void finish(long operation,Callback callback,String code){if(!current(operation))return;busy=false;listener.changed();callback.complete(code);}
    void load(Callback callback){long operation=begin(callback);if(operation<0)return;disk.execute(()->{try{PendingDeviceRecoveryStore.Pending value=store.load();long epoch=store.epoch();main.execute(()->{
        if(closed)return;pending=value;pendingEpoch=epoch;loaded=true;failed=false;listener.changed();finish(operation,callback,"");
    });}catch(Exception failure){localFailure(operation,callback);}});}
    void start(String target,String oldCapability,Callback callback){
        if(!loaded||failed){callback.complete("RECOVERY_CHECKPOINT_UNAVAILABLE");return;}
        if(session==null||!origin.equals(session.serverOnion)||!PendingDeviceRecoverySchema.id(target)||!SessionSchema.token(oldCapability,"gd_acct_")){callback.complete("RECOVERY_INPUT_OR_SESSION_INVALID");return;}
        long operation=begin(callback);if(operation<0)return;AppSessionStore.Session owner=session;String bound=origin;preparing=true;listener.changed();
        disk.execute(()->{try{PendingDeviceRecoveryStore.Pending value=store.prepare(bound,owner.accountId,target,oldCapability);long epoch=store.epoch();main.execute(()->{
            preparing=false;if(closed)return;pending=value;pendingEpoch=epoch;loaded=true;failed=false;listener.changed();
            if(!current(operation))return;if(!matchesContext(value)){finish(operation,callback,"RECOVERY_CONTEXT_CHANGED");return;}recover(operation,value,session,true,callback);
        });}catch(Exception failure){main.execute(()->preparing=false);localFailure(operation,callback);}});
    }
    void resume(Callback callback){
        if(!loaded||failed||pending==null){callback.complete("RECOVERY_CHECKPOINT_UNAVAILABLE");return;}
        if(!pending.serverOnion.equals(origin)||session!=null&&!matchesContext(pending)){callback.complete("RECOVERY_CONTEXT_CHANGED");return;}
        long operation=begin(callback);if(operation<0)return;PendingDeviceRecoveryStore.Pending expected=pending;preparing=true;
        disk.execute(()->{try{PendingDeviceRecoveryStore.Pending value=store.load();long epoch=store.epoch();main.execute(()->{
            preparing=false;if(closed)return;pending=value;pendingEpoch=epoch;loaded=true;failed=false;listener.changed();if(!current(operation))return;
            if(!sameRequest(expected,value)){finish(operation,callback,"RECOVERY_STATE_CHANGED");return;}
            if(!matchesContext(value)){finish(operation,callback,"RECOVERY_CONTEXT_CHANGED");return;}
            if(session==null)authenticate(operation,value,true,callback);else recover(operation,value,session,true,callback);
        });}catch(Exception failure){main.execute(()->preparing=false);localFailure(operation,callback);}});
    }
    private static boolean sameRequest(PendingDeviceRecoveryStore.Pending a,PendingDeviceRecoveryStore.Pending b){return a!=null&&b!=null&&a.serverOnion.equals(b.serverOnion)&&a.accountId.equals(b.accountId)&&a.targetDeviceId.equals(b.targetDeviceId)&&a.recoveryId.equals(b.recoveryId)&&a.oldAccountToken.equals(b.oldAccountToken)&&a.replacementAccountToken.equals(b.replacementAccountToken);}
    private boolean matchesContext(PendingDeviceRecoveryStore.Pending value){return value.serverOnion.equals(origin)&&(session==null||value.serverOnion.equals(session.serverOnion)&&value.accountId.equals(session.accountId));}
    private void recover(long operation,PendingDeviceRecoveryStore.Pending value,AppSessionStore.Session owner,boolean mayAuthenticate,Callback callback){
        if(!current(operation))return;if(owner==null||!matchesContext(value)||!owner.serverOnion.equals(value.serverOnion)||!owner.accountId.equals(value.accountId)){finish(operation,callback,"RECOVERY_CONTEXT_CHANGED");return;}
        dispatch(operation,callback,()->api.request("POST","/v1/devices/"+value.targetDeviceId+"/recover",request(value),owner,value.serverOnion,(status,body,code)->{
            if(!current(operation))return;
            if(unauthorized(status,body,code)&&mayAuthenticate){authenticate(operation,value,true,callback);return;}
            if(status!=200||!code.isEmpty()){finish(operation,callback,unauthorized(status,body,code)?"RECOVERY_CREDENTIALS_UNRESOLVED":status==409?"RECOVERY_STATE_CHANGED":"RECOVERY_RESULT_UNCONFIRMED");return;}
            if(!validResult(body,value)){finish(operation,callback,"RECOVERY_INVALID_RESPONSE");return;}
            disk.execute(()->{try{PendingDeviceRecoveryStore.Pending accepted=store.markConfirmed(value.recoveryId);long epoch=store.epoch();main.execute(()->{
                if(closed)return;pending=accepted;pendingEpoch=epoch;loaded=true;failed=false;listener.changed();finish(operation,callback,"");
            });}catch(Exception failure){localFailure(operation,callback);}});
        }));
    }
    private void authenticate(long operation,PendingDeviceRecoveryStore.Pending value,boolean replacement,Callback callback){
        if(!current(operation))return;if(!matchesContext(value)){finish(operation,callback,"RECOVERY_CONTEXT_CHANGED");return;}
        JSONObject body=object("account_id",value.accountId,"account_token",replacement?value.replacementAccountToken:value.oldAccountToken);
        dispatch(operation,callback,()->api.request("POST","/v1/auth/sessions",body,null,value.serverOnion,(status,response,code)->{
            if(!current(operation))return;if(unauthorized(status,response,code)&&replacement){authenticate(operation,value,false,callback);return;}
            if(status!=201||!code.isEmpty()){finish(operation,callback,unauthorized(status,response,code)?"RECOVERY_CREDENTIALS_UNRESOLVED":"RECOVERY_RESULT_UNCONFIRMED");return;}
            final AppSessionStore.Session authenticated;try{authenticated=NativeResponseRules.session(value.serverOnion,value.accountId,response);}catch(IllegalArgumentException malformed){finish(operation,callback,"RECOVERY_INVALID_RESPONSE");return;}
            session=authenticated;listener.sessionAuthenticated(authenticated);if(!current(operation))return;recover(operation,value,session,false,callback);
        }));
    }
    void acknowledge(String recoveryId,Callback callback){
        if(!loaded||failed||pending==null||!pending.confirmed||!pending.recoveryId.equals(recoveryId)||!matchesContext(pending)){callback.complete("RECOVERY_ACK_NOT_ALLOWED");return;}
        long operation=begin(callback);if(operation<0)return;disk.execute(()->{try{store.acknowledge(recoveryId);PendingDeviceRecoveryStore.Pending value=store.load();long epoch=store.epoch();main.execute(()->{
            if(closed)return;pending=value;pendingEpoch=epoch;loaded=true;failed=false;listener.changed();finish(operation,callback,"");
        });}catch(Exception failure){localFailure(operation,callback);}});
    }
    /** UI must first show every readable checkpoint key and obtain explicit warned abandonment. */
    void resetAfterConfirmation(Callback callback){String expected=failed||pending==null?null:pending.recoveryId;long operation=begin(callback);if(operation<0)return;disk.execute(()->{try{store.resetAfterConfirmation(expected);main.execute(()->{
        if(closed)return;pending=null;loaded=true;failed=false;listener.changed();finish(operation,callback,"");
    });}catch(Exception failure){localFailure(operation,callback);}});}
    private void dispatch(long operation,Callback callback,Runnable request){
        if(!current(operation))return;
        if(!store.dispatchIfCurrent(pendingEpoch,request))finish(operation,callback,"RECOVERY_STATE_CHANGED");
    }
    private void localFailure(long operation,Callback callback){main.execute(()->{if(closed)return;failed=true;loaded=false;listener.changed();finish(operation,callback,"RECOVERY_CHECKPOINT_UNCERTAIN");});}
    private static boolean unauthorized(int status,JSONObject body,String code){return status==401&&code.isEmpty()&&body!=null&&body.length()==1&&"UNAUTHORIZED".equals(body.opt("code"));}
    private static boolean validResult(JSONObject body,PendingDeviceRecoveryStore.Pending value){return body!=null&&body.length()==3&&"recovered".equals(body.opt("status"))&&value.targetDeviceId.equals(body.opt("device_id"))&&value.recoveryId.equals(body.opt("recovery_id"));}
    private static JSONObject request(PendingDeviceRecoveryStore.Pending value){return object("account_token",value.oldAccountToken,"replacement_account_token",value.replacementAccountToken,"recovery_id",value.recoveryId);}
    private static JSONObject object(String... fields){JSONObject result=new JSONObject();try{for(int i=0;i<fields.length;i+=2)result.put(fields[i],fields[i+1]);return result;}catch(Exception impossible){throw new IllegalArgumentException("INVALID_RECOVERY_REQUEST");}}
}
