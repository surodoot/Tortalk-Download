package dev.gyeopdam.embeddedtor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.os.PersistableBundle;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.widget.Button;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.os.CancellationSignal;
import android.widget.ImageView;

/** Original packaged web design, rendered locally. All authority and transport remain native. */
public final class TorTalkActivity extends Activity {
    private static final String ORIGIN="https://appassets.androidplatform.net";
    private static final String PAGE=ORIGIN+"/ui/index.html";
    private static final class BridgeDocument {final long generation;final String nonce;BridgeDocument(long generation){this.generation=generation;nonce=UUID.randomUUID().toString().replace("-","");}}
    private final Object documentLock=new Object();
    private long documentGeneration;
    private volatile BridgeDocument bridgeDocument;
    private BridgeDocument draftDocument;
    private WebView web;
    private FrameLayout contentHost;
    private WebMessagePort nativePort;
    private EmbeddedTorController controller;
    private TorApiClient api;
    private MessageCoordinator messaging;
    private PeerBlockCoordinator peerBlocks;
    private DeviceRecoveryCoordinator deviceRecovery;
    private boolean localHistoryMode;
    private String localHistoryPeer="",localHistoryBefore="";
    private boolean enrolling, enrollmentReady, enrollmentAttempted;
    private AppSessionStore storage;
    private PendingAccountStore pendingStore;
    private ServerConfigStore serverConfig;
    private PendingAccountStore.Pending pendingEnrollment;
    private AppSessionStore.Session session;
    private final Executor disk=AccountWorkQueue.INSTANCE;
    private static final int CONTACT_QR_REQUEST=0x5451;
    private final ExecutorService qrWorker=Executors.newSingleThreadExecutor();
    private Future<?> qrTask;private CancellationSignal qrCancellation;private Runnable qrTimeout;
    private long qrGeneration;private boolean awaitingQr;
    private String qrOrigin="",qrAccount="";private MessageCoordinator.Peer qrPeer;
    private ContactCard pendingQrCard;

    private final TorTalkState flow=new TorTalkState();
    private String lastConnection="";private boolean lastSendClosed;
    private final Runnable changed=()->{if(api==null)return;String current=connectionLabel();boolean closed=sendClosed();if(!current.equals(lastConnection)||closed!=lastSendClosed){lastConnection=current;lastSendClosed=closed;publish(null);}maybeEnroll();};
    private AlertDialog dialog;
    private boolean destroyed, persistSession, storageBlocked, pendingStorageBlocked, serverConfigBlocked, foreground;
    private boolean initializing=true, startupNavigated;
    private String route="welcome", error="", selectedRoom="", lastPublished="";
    private final DraftLedger draftLedger=new DraftLedger();
    private int historyEnd=-1;
    private final ArrayDeque<Integer> newerHistory=new ArrayDeque<>();
    private String pendingRecoveryAccount="", pendingRecoveryToken="";
    private boolean pendingRecoverySignedIn;
    private JSONArray rooms=new JSONArray(), messages=new JSONArray();
    private static final class Retained {
        final AppSessionStore.Session session;final boolean persist,archive;final String route,room,archivePeer,archiveBefore;final JSONArray rooms;
        Retained(AppSessionStore.Session s,boolean p,String r,String selected,JSONArray list,boolean archive,String archivePeer,String archiveBefore){session=s;persist=p;route=r;room=selected;rooms=list;this.archive=archive;this.archivePeer=archivePeer;this.archiveBefore=archiveBefore;}
    }
    private final Handler timer=new Handler(Looper.getMainLooper());
    private long pollDelay=10000,maintenanceDelay=60000;
    private boolean maintaining;
    private final Runnable maintainer=new Runnable(){@Override public void run(){if(!foreground||destroyed)return;if(!localHistoryMode&&!recoveryHoldsEnrollment()&&session!=null&&enrollmentReady&&!enrolling&&!maintaining&&!flow.busy&&controller.phase()==TorReadinessGate.Phase.READY){maintaining=true;messaging.maintainPrekeys(code->{maintaining=false;if(expireFromCoordinator(code))return;maintenanceDelay=code.isEmpty()?60000:Math.min(300000,maintenanceDelay*2);});}timer.postDelayed(this,maintenanceDelay);}};
    private final Runnable poller=new Runnable(){@Override public void run(){if(!foreground||destroyed)return;if(!localHistoryMode&&protectionMessage().isEmpty()&&route.equals("chat")&&session!=null&&!flow.busy&&controller.phase()==TorReadinessGate.Phase.READY&&messaging.isReady(selectedRoom))refreshMessages();timer.postDelayed(this,pollDelay);}};

    // Script executes only pinned packaged assets behind CSP, blocked network, and the typed nonce-bound port.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(0xfff7f6f0);getWindow().setNavigationBarColor(0xfff7f6f0);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        controller=EmbeddedTorController.get(this);controller.addListener(changed);api=new TorApiClient(controller);storage=new AppSessionStore(this);pendingStore=new PendingAccountStore(this);serverConfig=new ServerConfigStore(this);
        messaging=new MessageCoordinator(this,api,new MessageCoordinator.Listener(){
            @Override public void sessionChanged(AppSessionStore.Session value){session=value;if(peerBlocks!=null)peerBlocks.bindSession(value);if(deviceRecovery!=null)deviceRecovery.updateContext(api.configuredOnion(),value);if(persistSession)saveSession();publish(null);}
            @Override public void changed(){updateMessages(messaging.messagesForRoom(selectedRoom));publish(null);}
        });
        peerBlocks=new PeerBlockCoordinator((origin,account)->{
            PeerBlockJournal journal=new PeerBlockJournal(getApplicationContext(),origin,account);
            return new PeerBlockCoordinator.Journal(){
                public java.util.List<PeerBlockJournal.Pending> load()throws java.io.IOException{return journal.load();}
                public java.util.List<PeerBlockJournal.Status> loadStatuses()throws java.io.IOException{return journal.loadStatuses();}
                public void prepare(PeerBlockJournal.Pending request)throws java.io.IOException{journal.prepare(request);}
                public void confirm(PeerBlockJournal.Status status,String mutation)throws java.io.IOException{journal.confirm(status,mutation);}
                public void confirmAll(java.util.List<PeerBlockJournal.Status> statuses)throws java.io.IOException{journal.confirmAll(statuses);}
                public void acknowledge(String peer,String mutation)throws java.io.IOException{journal.acknowledge(peer,mutation);}
                public void resetAfterConfirmation()throws java.io.IOException{journal.resetAfterConfirmation();}
            };
        },(method,path,body,owner,reply)->api.request(method,path,body,owner.sessionToken,owner.deviceToken,owner.serverOnion,reply::complete),disk,timer::post,()->{markKnownBlockedRooms();publish(null);});
        PendingDeviceRecoveryStore recoveryStore=new PendingDeviceRecoveryStore(getApplicationContext());
        deviceRecovery=new DeviceRecoveryCoordinator(new DeviceRecoveryCoordinator.Store(){
            public PendingDeviceRecoveryStore.Pending load()throws java.io.IOException{return recoveryStore.load();}
            public PendingDeviceRecoveryStore.Pending prepare(String origin,String account,String target,String oldCapability)throws java.io.IOException{return recoveryStore.prepare(origin,account,target,oldCapability);}
            public PendingDeviceRecoveryStore.Pending markConfirmed(String id)throws java.io.IOException{return recoveryStore.markConfirmed(id);}
            public void acknowledge(String id)throws java.io.IOException{recoveryStore.acknowledge(id);}
            public void resetAfterConfirmation(String id)throws java.io.IOException{recoveryStore.resetAfterConfirmation(id);}
            public long epoch(){return PendingDeviceRecoveryStore.mutationEpoch();}
            public boolean dispatchIfCurrent(long expected,Runnable request){return PendingDeviceRecoveryStore.dispatchIfCurrent(expected,request);}
        },(method,path,body,owner,origin,reply)->{
            if(!origin.equals(api.configuredOnion())){reply.complete(0,new JSONObject(),"RECOVERY_CONTEXT_CHANGED");return;}
            api.request(method,path,body,owner==null?"":owner.sessionToken,owner==null?"":owner.deviceToken,origin,reply::complete);
        },disk,timer::post,new DeviceRecoveryCoordinator.Listener(){
            public void changed(){publish(null);}
            public void sessionAuthenticated(AppSessionStore.Session value){installRecoverySession(value);}
        });
        web=new WebView(this);WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);settings.setBlockNetworkImage(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setDomStorageEnabled(false);settings.setDatabaseEnabled(false);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
        settings.setSaveFormData(false);settings.setGeolocationEnabled(false);
        WebView.setWebContentsDebuggingEnabled(false);CookieManager.getInstance().setAcceptCookie(false);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);web.setSaveEnabled(false);
        web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return asset(request);}
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){if(destroyed||view!=web)return;closePort();if(!PAGE.equals(url))view.stopLoading();}
            @Override public void onPageFinished(WebView view,String url){if(!destroyed&&web!=null&&view==web&&PAGE.equals(url)&&PAGE.equals(view.getUrl()))connectPort();}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){if(destroyed||view!=web)return true;closePort();flow.cancel();peerBlocks.cancel();if(deviceRecovery!=null)deviceRecovery.cancel();enrolling=false;maintaining=false;messaging.cancel();timer.removeCallbacks(poller);timer.removeCallbacks(maintainer);route="welcome";if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);view.destroy();web=null;LinearLayout fallback=form();TextView title=new TextView(TorTalkActivity.this);title.setText("화면을 다시 불러와주세요.\n진행 중이던 요청은 중단했어요.");fallback.addView(title);Button retry=new Button(TorTalkActivity.this);retry.setText("화면 다시 열기");retry.setOnClickListener(v->recreate());fallback.addView(retry);contentHost.removeAllViews();contentHost.addView(fallback,new FrameLayout.LayoutParams(-1,-1));return true;}
            @Override public void onReceivedSslError(WebView view,android.webkit.SslErrorHandler handler,android.net.http.SslError ssl){handler.cancel();}
        });
        contentHost=TorTalkInsets.host(this,web);setContentView(contentHost);web.loadUrl(PAGE);
        final Retained retained=getLastNonConfigurationInstance() instanceof Retained?(Retained)getLastNonConfigurationInstance():null;

        disk.execute(()->{
            AppSessionStore.Session loadedSession=null;PendingAccountStore.Pending loadedPending=null;
            ServerConfigStore.Config loadedConfig=null;boolean sessionFailed=false,pendingFailed=false,configFailed=false;
            try{loadedConfig=serverConfig.load();}catch(Exception failure){configFailed=true;}
            try{loadedSession=storage.load();}catch(Exception failure){sessionFailed=true;}
            try{loadedPending=pendingStore.load();}catch(Exception failure){pendingFailed=true;}
            final AppSessionStore.Session restored=loadedSession;final PendingAccountStore.Pending pending=loadedPending;
            final boolean loginFailed=sessionFailed,enrollmentFailed=pendingFailed,configurationFailed=configFailed;final ServerConfigStore.Config config=loadedConfig;
            runOnUiThread(()->{if(destroyed)return;initializing=false;storageBlocked=loginFailed||!AccountWorkQueue.restorationAllowed();pendingStorageBlocked=enrollmentFailed;serverConfigBlocked=configurationFailed;
                if(config!=null)api.configureOnion(config.serverOnion);
                error=configurationFailed?"저장된 서버 설정을 열 수 없어요. 서버 연결의 저장 정보 복구를 확인해주세요.":enrollmentFailed?"중단된 계정의 복구 정보를 열 수 없어요. 설정의 서버 연결에서 저장 정보 복구를 확인해주세요.":storageBlocked?"저장된 로그인 정보를 열 수 없어요. 설정의 서버 연결에서 저장 정보 복구를 확인해주세요.":"";
                pendingEnrollment=pending;boolean hasRetainedSession=retained!=null&&retained.session!=null;AppSessionStore.Session candidate=hasRetainedSession?retained.session:restored;if(!configurationFailed&&!storageBlocked&&candidate!=null&&candidate.serverOnion.equals(api.configuredOnion())){session=candidate;messaging.bindSession(session);peerBlocks.bindSession(session);enrollmentReady=false;enrollmentAttempted=false;persistSession=hasRetainedSession?retained.persist:true;flow.signedIn(false);if(hasRetainedSession)restoreRetainedView(retained);else if(!startupNavigated)route="chats";}
                deviceRecovery.updateContext(api.configuredOnion(),session);deviceRecovery.load(code->{if(!code.isEmpty())error=getString(R.string.device_recovery_storage_error);if(foreground&&pendingEnrollment==null&&deviceRecovery.pending()!=null)deviceRecoveryDialog();publish(null);});if(pendingEnrollment!=null)resumePendingDialog();publish(null);if(localHistoryMode&&route.equals("chat"))openLocalHistory(selectedRoom,localHistoryPeer,localHistoryBefore);
            });
        });
    }
    private void restoreRetainedView(Retained retained){
        if(!startupNavigated){route=retained.route;localHistoryMode=retained.archive&&route.equals("chat");localHistoryPeer=retained.archivePeer;localHistoryBefore=retained.archiveBefore;}
        selectedRoom=retained.archive&&!localHistoryMode?"":retained.room;rooms=retained.rooms;
    }
    private WebResourceResponse asset(WebResourceRequest request){
        Uri uri=request.getUrl();String path=uri.getPath();
        if("GET".equals(request.getMethod())&&"https".equals(uri.getScheme())&&"appassets.androidplatform.net".equals(uri.getHost())&&uri.getPort()==-1&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null&&
                (("/ui/index.html".equals(path)&&request.isForMainFrame())||(!request.isForMainFrame()&&("/ui/styles.css".equals(path)||"/ui/native.css".equals(path)||"/ui/app.js".equals(path))))){
            try{BridgeDocument document=null;if(path.equals("/ui/index.html")){synchronized(documentLock){document=new BridgeDocument(++documentGeneration);bridgeDocument=document;}}return new WebResourceResponse(path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":"text/html","UTF-8",200,"OK",Collections.singletonMap("Cache-Control","no-store"),assetBytes(path,document));}catch(Exception ignored){}
        }
        return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
    }
    private InputStream assetBytes(String path,BridgeDocument document)throws java.io.IOException {InputStream stream=getAssets().open(path.substring(1));if(!path.equals("/ui/index.html"))return stream;try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=stream.read(buffer))!=-1){if(out.size()+n>16384)throw new java.io.IOException("ASSET_TOO_LARGE");out.write(buffer,0,n);}String html=new String(out.toByteArray(),StandardCharsets.UTF_8).replace("__TORTALK_BRIDGE_NONCE__",document.nonce);return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));}finally{stream.close();}}
    private void connectPort(){
        final BridgeDocument document=bridgeDocument;if(document==null)return;if(draftDocument!=document){draftLedger.reset();draftDocument=document;}closePort();WebMessagePort[] channel=web.createWebMessageChannel();nativePort=channel[0];
        nativePort.setWebMessageCallback(new WebMessagePort.WebMessageCallback(){@Override public void onMessage(WebMessagePort port,WebMessage message){
            if(destroyed||!foreground||document!=bridgeDocument||port!=nativePort||web==null||!PAGE.equals(web.getUrl())||message.getPorts()!=null&&message.getPorts().length!=0)return;
            String data=message.getData();if(data==null||data.length()>UiCommandPolicy.MAX_COMMAND_CHARS||!JsonBounds.safe(data))return;
            try{JSONObject command=new JSONObject(data);if(!(command.opt("action") instanceof String))return;String action=command.getString("action");if(!UiCommandPolicy.allows(action))return;
                String valueKey=action.equals("route")?"route":action.equals("open_room")?"roomId":action.equals("send_message")?"text":null;
                int expected=action.equals("send_message")?3:valueKey==null?1:2;if(command.length()!=expected||valueKey!=null&&!(command.opt(valueKey) instanceof String))return;
                if(action.equals("send_message")&&(!(command.opt("draftId") instanceof String)||!UiCommandPolicy.validDraftId(command.getString("draftId"))))return;dispatch(action,command);
            }catch(Exception ignored){/* Never log untrusted commands or credentials. */}
        }});
        web.postWebMessage(new WebMessage("tortalk-port-v1:"+document.nonce,new WebMessagePort[]{channel[1]}),Uri.parse(ORIGIN));
    }
    private void closePort(){lastPublished="";if(nativePort!=null){nativePort.close();nativePort=null;}}
    private static JSONObject object(String...pairs){JSONObject value=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)value.put(pairs[i],pairs[i+1]);}catch(Exception ignored){}return value;}
    private void dispatch(String action,JSONObject data){
        if(action.equals("ready")){publish(null);return;}
        if(initializing&&!action.equals("route")&&!action.equals("developer")){error="기기의 계정 정보를 확인하고 있어요. 잠시 후 다시 시도해주세요.";publish(null);return;}
        if(!pendingRecoveryToken.isEmpty())return;
        if(action.equals("route")){String next=data.optString("route");if(UiCommandPolicy.validRoute(next)){if(localHistoryMode&&!next.equals("chat"))leaveLocalHistory();route=next;startupNavigated=true;flow.cancel();peerBlocks.cancel();if(deviceRecovery!=null)deviceRecovery.cancel();messaging.cancel();enrolling=false;maintaining=false;api.cancelAll();publish(null);}return;}
        if(deviceRecovery!=null&&deviceRecovery.busy()){publish(null);return;}
        switch(action){
            case "begin": if(session!=null){publish("chats");return;}signupDialog();break;
            case "login": loginDialog();break;
            case "setup": setupDialog();break;
            case "developer": if(route.equals("settings"))startActivity(new Intent(this,TorTestActivity.class));break;
            case "logout": if(session!=null)logoutDialog();break;
            case "refresh": if(session!=null){if(route.equals("chat"))refreshMessages();else refreshRooms();}break;
            case "create_invite": if(session!=null)createInviteDialog();break;
            case "accept_invite": if(session!=null)acceptInviteDialog();break;
            case "history_older": if(session!=null&&route.equals("chat")){HistoryWindow page=HistoryWindow.of(messages,historyEnd);if(page.start>0){newerHistory.push(historyEnd);historyEnd=page.start;publish(null);}else if(localHistoryMode&&!messaging.localArchiveNextBefore().isEmpty())openLocalHistory(selectedRoom,localHistoryPeer,messaging.localArchiveNextBefore());}break;
            case "history_newer": if(session!=null&&route.equals("chat")){if(newerHistory.isEmpty()&&localHistoryMode&&!localHistoryBefore.isEmpty())openLocalHistory(selectedRoom,localHistoryPeer,"");else{historyEnd=newerHistory.isEmpty()?-1:newerHistory.pop();publish(null);}}break;
            case "devices": if(session!=null)devicesDialog();break;
            case "contact": if(session!=null)contactActionsDialog();break;
            case "open_room": if(session!=null&&UiCommandPolicy.validOpaqueId(data.optString("roomId")))openRoom(data.optString("roomId"));break;
            case "retry_pending": if(session!=null)retryPending();break;
            case "send_message": if(session!=null)sendMessage(data.optString("text",""),data.optString("draftId",""));break;
            case "verify_peer": if(session!=null)peerActionsDialog();break;
        }
    }
    private void publish(String next){
        if(next!=null)route=next;if(destroyed||!foreground||nativePort==null)return;
        try{HistoryWindow history=HistoryWindow.of(route.equals("chat")?messages:new JSONArray(),historyEnd);JSONObject state=new JSONObject();state.put("authenticated",session!=null);state.put("accountId",session==null?"":session.accountId);
            state.put("connection",connectionLabel());state.put("connected",controller.phase()==TorReadinessGate.Phase.READY);state.put("busy",flow.busy||initializing||deviceRecovery!=null&&deviceRecovery.busy());state.put("error",error);if(!route.equals("chat"))state.put("rooms",rooms);state.put("messages",history.items);state.put("historyLimited",history.total>history.items.length());state.put("historyCanOlder",history.start>0||localHistoryMode&&!messaging.localArchiveNextBefore().isEmpty());state.put("historyCanNewer",route.equals("chat")&&(!newerHistory.isEmpty()||localHistoryMode&&!localHistoryBefore.isEmpty()));state.put("historyPage",historyEnd);state.put("roomId",selectedRoom);state.put("cryptoReady",messaging!=null&&messaging.isReady(selectedRoom));
            String peerId="";for(int i=0;i<rooms.length();i++){JSONObject room=rooms.optJSONObject(i);if(room!=null&&selectedRoom.equals(room.optString("room_id")))peerId=room.optString("peer_id");}state.put("peerId",peerId);state.put("sendClosed",sendClosed());state.put("historyOnly",localHistoryMode);state.put("protectionMessage",protectionMessage());if(localHistoryMode)state.put("peerId",localHistoryPeer);
            String payload=new JSONObject().put("type","state").put("state",state).put("route",route).toString();
            if(payload.length()>262144)return;if(!payload.equals(lastPublished)){lastPublished=payload;nativePort.postMessage(new WebMessage(payload));}
        }catch(Exception ignored){}
    }
    private String connectionLabel(){
        if(initializing)return "기기의 계정 정보를 확인하고 있어요";
        switch(controller.nativeStopState()){
            case STOP_REQUESTED:return getString(R.string.tor_stop_requested);
            case OFFLINE_CONFIRMED:return getString(R.string.tor_offline_confirmed);
            case TERMINATION_UNCONFIRMED:return getString(R.string.tor_termination_unconfirmed);
            case TERMINATED_CONFIRMED:return getString(R.string.tor_terminated_confirmed);
            default:break;
        }
        if(api.configuredOnion().isEmpty())return "서버를 연결해주세요";
        switch(controller.phase()){case READY:return "Tor 연결됨";case STARTING:return "Tor 연결 중 · "+controller.progress()+"%";case FAILED:return "Tor 연결 실패 · 설정에서 다시 시도해주세요";default:return "Tor 연결을 시작해주세요";}
    }

    private LinearLayout form(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);int pad=(int)(24*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad/2,pad,pad/2);return layout;}
    private EditText field(LinearLayout form,String hint,boolean secret){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(14);e.setSingleLine(true);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);e.setImeOptions(e.getImeOptions()|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);e.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));form.addView(e);return e;}
    private android.widget.ScrollView scrollForm(LinearLayout fields){
        android.widget.ScrollView scroll=new android.widget.ScrollView(this){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int cap=Math.max(1,(int)(getResources().getDisplayMetrics().heightPixels*0.45f));
                int available=View.MeasureSpec.getMode(heightSpec)==View.MeasureSpec.UNSPECIFIED?cap:Math.min(cap,View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setSaveEnabled(false);scroll.setSaveFromParentEnabled(false);scroll.addView(fields);
        return scroll;
    }
    private CheckBox choice(LinearLayout form,String text){CheckBox c=new CheckBox(this);c.setText(text);c.setChecked(false);form.addView(c);return c;}
    private void secureDialog(AlertDialog value){if(dialog!=null)dialog.dismiss();dialog=value;if(value.getWindow()!=null){value.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);value.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}value.show();}
    private void notice(String title,String message){secureDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("확인",null).create());}
    private void copyExplicit(String label,String value){ClipboardManager clipboard=getSystemService(ClipboardManager.class);if(clipboard==null)return;try{ClipData clip=ClipData.newPlainText(label,value);PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);clipboard.setPrimaryClip(clip);Toast.makeText(this,"복사했어요. 신뢰하는 곳에만 붙여넣어주세요.",Toast.LENGTH_SHORT).show();}catch(RuntimeException failure){Toast.makeText(this,"복사하지 못했어요.",Toast.LENGTH_SHORT).show();}}
    private void noticeWithCopy(String title,String message,String value){AlertDialog box=new AlertDialog.Builder(this).setTitle(title).setMessage(message+"\n\n복사하면 클립보드를 읽을 수 있는 다른 앱에 노출될 수 있어요.").setPositiveButton("확인",null).setNeutralButton("복사",null).create();secureDialog(box);box.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->copyExplicit(title,value));}
    private void setupDialog(){
        LinearLayout fields=form();EditText endpoint=field(fields,"http://…onion",false);endpoint.setText(api.configuredOnion());EditText admission=field(fields,"서버 가입 코드 (새 계정용)",true);
        secureDialog(new AlertDialog.Builder(this).setTitle("서버 연결").setMessage("Tor v3 .onion 주소만 사용할 수 있어요. 가입 코드는 기기에 암호화하여 저장해요. 같은 서버에서 비워두면 기존 코드를 유지해요. 서버를 바꾸면 로그아웃해요.").setView(scrollForm(fields)).setNegativeButton("취소",null).setPositiveButton("저장하고 연결",null).setNeutralButton("저장 정보 복구",(d,w)->storedDataDialog()).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String next=endpoint.getText().toString().trim();String before=api.configuredOnion();final String canonical;try{canonical=OnionApiRequest.canonicalOrigin(next);}catch(IllegalArgumentException invalid){endpoint.setError("올바른 Tor v3 onion 주소를 입력해주세요");return;}if(pendingEnrollment!=null&&!canonical.equals(pendingEnrollment.serverOnion)){endpoint.setError("진행 중인 계정을 만들던 서버 주소를 입력해주세요");return;}String code=admission.getText().toString().trim();admission.setText("");dialog.dismiss();commitServerConfig(before,canonical,code);});
    }
    private void commitServerConfig(String before,String canonical,String code){
        boolean changing=!before.equals(canonical);
        if(changing)clearSession();
        final long cleanup=changing?AccountWorkQueue.beginCleanup():0;
        if(changing)storageBlocked=true;
        long operation=flow.begin();if(operation<0)return;publish(changing?"welcome":null);
        disk.execute(()->{
            boolean saved=false;try{serverConfig.save(canonical,code);saved=true;}catch(Exception failure){/* Fixed UI status only. */}
            // Ordered with the origin commit, not dependent on a surviving Activity callback.
            if(changing){try{storage.clear();AccountWorkQueue.completeCleanup(cleanup);}catch(Exception failure){/* Keep restore fence closed. */}}
            final boolean committed=saved;
            runOnUiThread(()->{if(destroyed)return;boolean continueAction=flow.complete(operation);
                if(changing)storageBlocked=!AccountWorkQueue.restorationAllowed();
                if(!committed){error="서버 설정을 저장하지 못했어요. 주소와 가입 코드를 확인해주세요.";publish(null);return;}
                if(!api.configureOnion(canonical)){failed(0,"INVALID_SERVER");return;}
                serverConfigBlocked=false;deviceRecovery.updateContext(api.configuredOnion(),session);enrollmentAttempted=false;if(continueAction&&foreground)controller.start();
                error=storageBlocked?"이전 서버의 저장된 로그인을 삭제하지 못했어요. 설정에서 저장 정보를 복구해주세요.":continueAction?"":"서버 설정을 저장했어요. 연결하려면 다시 연결을 선택해주세요.";
                publish(changing?"welcome":null);
            });
        });
    }

    private boolean ready(){if(serverConfigBlocked){notice("서버 설정 확인","저장된 서버 설정을 열 수 없어요. 설정의 서버 연결에서 저장 정보 복구를 확인해주세요.");return false;}if(api.configuredOnion().isEmpty()){setupDialog();return false;}if(controller.phase()!=TorReadinessGate.Phase.READY){error="Tor 연결이 준비된 뒤 다시 시도해주세요.";controller.start();publish(null);return false;}if(pendingStorageBlocked){notice("중단된 계정 복구 정보 확인","계정 생성 중 보관한 복구 정보를 열 수 없어요. 설정의 서버 연결에서 저장 정보 복구를 확인해주세요. 자동으로 삭제하지 않아요.");return false;}if(storageBlocked){notice("저장된 로그인 확인","저장된 로그인 정보를 열 수 없어요. 설정에서 저장된 로그인을 삭제한 뒤 다시 시도해주세요.");return false;}return true;}
    private void signupDialog(){if(!ready())return;LinearLayout fields=form();CheckBox remember=choice(fields,"이 기기에 로그인 유지 (암호화하여 저장)");secureDialog(new AlertDialog.Builder(this).setTitle("새 계정 만들기").setMessage("전화번호와 이메일 없이 계정과 복구키를 발급해요. 복구키를 잃으면 계정 접근을 되찾을 수 없어요.").setView(scrollForm(fields)).setNegativeButton("취소",null).setPositiveButton("계정 만들기",(d,w)->signup(remember.isChecked())).create());}
    private void signup(boolean remember){long request=flow.begin();if(request<0)return;error="";publish(null);String origin=api.configuredOnion();disk.execute(()->{try{ServerConfigStore.Config config=serverConfig.load();if(config==null||!origin.equals(config.serverOnion)||config.registrationToken.isEmpty())throw new java.io.IOException("ADMISSION_REQUIRED");PendingAccountStore.Pending pending=pendingStore.loadOrCreate(origin);runOnUiThread(()->{if(destroyed||!flow.complete(request))return;pendingEnrollment=pending;registerPending(pending,remember);});}catch(Exception failure){runOnUiThread(()->{if(!flow.complete(request))return;error="계정을 만들 수 없어요. 설정의 서버 가입 코드와 기기 저장 상태를 확인해주세요.";publish(null);});}});}
    private void registerPending(PendingAccountStore.Pending pending,boolean remember){if(!pending.serverOnion.equals(api.configuredOnion())){notice("계정 생성 이어하기","처음 계정을 만들던 서버에 연결해주세요.");return;}long operation=flow.begin();if(operation<0)return;publish(null);disk.execute(()->{try{ServerConfigStore.Config config=serverConfig.load();if(config==null||!pending.serverOnion.equals(config.serverOnion)||config.registrationToken.isEmpty())throw new java.io.IOException("ADMISSION_REQUIRED");runOnUiThread(()->{if(destroyed||!flow.owns(operation))return;api.request("POST","/v1/accounts",object("account_id",pending.accountId,"account_token",pending.accountToken,"registration_token",config.registrationToken),"","",pending.serverOnion,(status,body,code)->{if(!flow.complete(operation))return;if(status<200||status>=300||!code.isEmpty()){error=status==503?"서버의 신규 가입이 열려 있지 않아요.":status==401?"서버 가입 코드를 확인해주세요.":"계정 생성을 확인하지 못했어요. 같은 복구키로 다시 시도할 수 있어요.";publish(null);return;}JSONObject account=body.optJSONObject("account");if(account==null||!pending.accountId.equals(account.optString("account_id"))){failed(0,"INVALID_RESPONSE");return;}authenticate(pending.accountId,pending.accountToken,remember,true);});});}catch(Exception failure){runOnUiThread(()->{if(!flow.complete(operation))return;error="설정에서 이 서버의 가입 코드를 입력해주세요.";publish(null);});}});}
    private void resumePendingDialog(){if(pendingEnrollment==null||!foreground)return;if(session!=null){if(session.accountId.equals(pendingEnrollment.accountId)&&session.serverOnion.equals(pendingEnrollment.serverOnion))showRecovery(pendingEnrollment.accountId,pendingEnrollment.accountToken,true);return;}secureDialog(new AlertDialog.Builder(this).setTitle("계정 생성 이어하기").setMessage("안전하게 보관한 복구키로 중단된 계정 생성을 이어갈 수 있어요. 같은 서버에서 같은 계정으로 다시 확인해요.\n\n서버\n"+pendingEnrollment.serverOnion).setNegativeButton("나중에",null).setPositiveButton("이어하기",(d,w)->{if(!pendingEnrollment.serverOnion.equals(api.configuredOnion())){notice("계정 생성 이어하기","처음 계정을 만들던 서버에 연결해주세요.");return;}if(ready())authenticate(pendingEnrollment.accountId,pendingEnrollment.accountToken,false,true,true);}).create());}
    private void loginDialog(){if(session!=null){notice("이미 로그인했어요","다른 계정으로 로그인하려면 설정에서 먼저 로그아웃해주세요.");return;}if(!ready())return;LinearLayout fields=form();EditText id=field(fields,"계정 ID",false),token=field(fields,"복구키",true);CheckBox remember=choice(fields,"이 기기에 로그인 유지 (암호화하여 저장)");secureDialog(new AlertDialog.Builder(this).setTitle("다시, 조용히.").setView(scrollForm(fields)).setNegativeButton("취소",(d,w)->token.setText("")).setPositiveButton("로그인",null).create());dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(session!=null){token.setText("");dialog.dismiss();notice("이미 로그인했어요","계정 정보를 다시 확인해주세요.");return;}String a=id.getText().toString().trim(),t=token.getText().toString().trim();if(a.length()<6||a.length()>80||t.length()<32||t.length()>256){token.setError("계정 ID와 복구키를 확인해주세요");return;}token.setText("");dialog.dismiss();authenticate(a,t,remember.isChecked(),false);});}
    private void authenticate(String account,String recovery,boolean remember,boolean created){authenticate(account,recovery,remember,created,false);}
    private void authenticate(String account,String recovery,boolean remember,boolean created,boolean resume){long request=flow.begin();if(request<0)return;publish(null);String origin=api.configuredOnion();api.request("POST","/v1/auth/sessions",object("account_id",account,"account_token",recovery),"","",origin,(status,body,code)->{if(!flow.complete(request))return;if(status<200||status>=300||!code.isEmpty()){if(resume&&status==401&&code.isEmpty()&&pendingEnrollment!=null&&account.equals(pendingEnrollment.accountId)&&origin.equals(pendingEnrollment.serverOnion)){registerPending(pendingEnrollment,remember);return;}if(created&&!resume)showRecovery(account,recovery,false);failed(status,code);return;}final AppSessionStore.Session accepted;try{accepted=NativeResponseRules.session(origin,account,body);}catch(IllegalArgumentException invalid){failed(0,"INVALID_RESPONSE");return;}rooms=new JSONArray();messages=new JSONArray();selectedRoom="";historyEnd=-1;newerHistory.clear();session=accepted;messaging.bindSession(session);peerBlocks.bindSession(session);deviceRecovery.updateContext(api.configuredOnion(),session);enrollmentReady=false;enrollmentAttempted=false;persistSession=remember;flow.signedIn(created);error="";if(remember)saveSession();maybeEnroll();if(created)showRecovery(account,recovery,true);else{publish("chats");refreshRooms();}});}
    private void showRecovery(String account,String recovery,boolean signedIn){pendingRecoveryAccount=account;pendingRecoveryToken=recovery;pendingRecoverySignedIn=signedIn;LinearLayout fields=form();TextView key=new TextView(this);key.setText(getString(R.string.recovery_credentials,account,recovery));key.setSaveEnabled(false);key.setTextIsSelectable(false);fields.addView(key);CheckBox saved=choice(fields,"계정 ID와 복구키를 안전한 곳에 기록했어요");AlertDialog box=new AlertDialog.Builder(this).setTitle("복구키를 보관해주세요").setMessage("복구키는 계정 접근용이며 잃어버린 대화 암호키를 복원하지 못해요. 복사하면 다른 앱이 클립보드를 읽을 수 있으니 신뢰하는 곳에만 보관해주세요.").setView(scrollForm(fields)).setCancelable(false).setNeutralButton("복구 정보 복사",null).setPositiveButton("계속",null).create();secureDialog(box);box.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->copyExplicit("Tor톡 복구 정보","계정 ID\n"+account+"\n복구키\n"+recovery));box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);saved.setOnCheckedChangeListener((v,on)->box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(on));box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{key.setText("");pendingRecoveryAccount="";pendingRecoveryToken="";pendingEnrollment=null;disk.execute(()->{try{pendingStore.acknowledge();}catch(Exception failure){runOnUiThread(()->{error="복구키 기록 확인 상태를 저장하지 못했어요. 다음 실행 시 다시 확인해요.";publish(null);});}});box.dismiss();if(signedIn&&session!=null&&session.accountId.equals(account)){publish("chats");refreshRooms();}else publish("welcome");});}
    private void saveSession(){final AppSessionStore.Session value=session;if(value==null)return;disk.execute(()->{try{storage.save(value);}catch(Exception failure){runOnUiThread(()->{if(session==null||!session.accountId.equals(value.accountId)||!session.serverOnion.equals(value.serverOnion)||!session.sessionToken.equals(value.sessionToken))return;error="로그인을 저장하지 못했어요. 앱을 닫으면 다시 로그인해야 해요.";persistSession=false;publish(null);});}});}
    private void storedDataDialog(){
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();java.util.ArrayList<Runnable> actions=new java.util.ArrayList<>();
        labels.add("저장된 로그인 삭제");actions.add(this::clearStored);
        labels.add("중단된 기기 복구 확인");actions.add(this::deviceRecoveryDialog);
        if(pendingStorageBlocked){labels.add("열 수 없는 계정 복구 정보 삭제");actions.add(this::abandonBrokenPendingDialog);}
        if(serverConfigBlocked){labels.add("열 수 없는 서버 설정 삭제");actions.add(this::clearBrokenServerDialog);}
        secureDialog(new AlertDialog.Builder(this).setTitle("저장 정보 복구").setItems(labels.toArray(new String[0]),(d,which)->actions.get(which).run()).setNegativeButton("취소",null).create());
    }
    private void clearBrokenServerDialog(){
        if(!serverConfigBlocked)return;secureDialog(new AlertDialog.Builder(this).setTitle("열 수 없는 서버 설정 삭제").setMessage("이 기기의 서버 주소와 가입 코드 기록을 삭제해요. 서버 주소와 가입 코드를 다시 입력해야 해요. 계정 복구 정보와 암호화된 대화 기록은 그대로 보관해요.").setNegativeButton("취소",null).setPositiveButton("설정 삭제",(d,w)->{
            clearSession();api.configureOnion("");publish("welcome");disk.execute(()->{try{serverConfig.clear();runOnUiThread(()->{if(destroyed)return;serverConfigBlocked=false;error="서버 설정을 삭제했어요. 주소와 가입 코드를 다시 입력해주세요.";publish(null);});}catch(Exception failure){runOnUiThread(()->{if(destroyed)return;error="서버 설정을 삭제하지 못했어요.";publish(null);});}});
        }).create());
    }
    private void abandonBrokenPendingDialog(){
        if(!pendingStorageBlocked)return;LinearLayout fields=form();CheckBox consent=choice(fields,"복구 정보를 따로 보관하지 않았다면 이 계정에 다시 접근하지 못할 수 있음을 이해했어요");
        AlertDialog box=new AlertDialog.Builder(this).setTitle("중단된 계정 복구 정보 삭제").setMessage("열 수 없는 계정 생성 기록을 이 기기에서 삭제해요. 서버에 계정이 이미 만들어졌을 수도 있어요. 이 기록에만 복구키가 있었다면 계정 접근을 되찾을 수 없어요. 서버 계정이나 대화 기록은 삭제하지 않아요.").setView(scrollForm(fields)).setNegativeButton("취소",null).setPositiveButton("위험을 이해하고 삭제",null).create();secureDialog(box);
        box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);consent.setOnCheckedChangeListener((v,on)->box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(on));
        box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{box.dismiss();disk.execute(()->{try{pendingStore.clear();runOnUiThread(()->{if(destroyed)return;pendingStorageBlocked=false;pendingEnrollment=null;pendingRecoveryAccount="";pendingRecoveryToken="";error="열 수 없는 계정 생성 기록을 삭제했어요.";publish(null);});}catch(Exception failure){runOnUiThread(()->{if(destroyed)return;error="계정 생성 기록을 삭제하지 못했어요. 기기 저장 상태를 확인하고 다시 시도해주세요.";publish(null);});}});});
    }
    private void clearStored(){clearSession();error="저장된 로그인을 삭제하고 있어요.";publish("welcome");deleteStoredSession("저장된 로그인을 삭제했어요.");}
    private void deleteStoredSession(){deleteStoredSession(null);}
    private void deleteStoredSession(String successMessage){
        final long cleanup=AccountWorkQueue.beginCleanup();storageBlocked=true;
        disk.execute(()->{try{storage.clear();AccountWorkQueue.completeCleanup(cleanup);runOnUiThread(()->{if(destroyed)return;storageBlocked=!AccountWorkQueue.restorationAllowed();if(session==null&&!storageBlocked&&successMessage!=null)error=successMessage;publish(null);});}
            catch(Exception failure){runOnUiThread(()->{if(destroyed)return;storageBlocked=true;error="저장된 로그인 삭제에 실패했어요. 앱을 다시 시작하면 로그인이 복원될 수 있어요. 설정에서 다시 삭제해주세요.";publish(null);});}});
    }

    private void clearSession(){cancelQr();flow.cancel();if(deviceRecovery!=null)deviceRecovery.cancel();if(peerBlocks!=null)peerBlocks.bindSession(null);localHistoryMode=false;localHistoryPeer="";localHistoryBefore="";draftLedger.reset();if(messaging!=null){messaging.cancel();messaging.bindSession(null);}api.cancelAll();flow.logout();pendingRecoveryAccount="";pendingRecoveryToken="";enrollmentReady=false;enrollmentAttempted=false;enrolling=false;maintaining=false;pollDelay=10000;maintenanceDelay=60000;session=null;if(deviceRecovery!=null)deviceRecovery.updateContext(api.configuredOnion(),null);persistSession=false;rooms=new JSONArray();messages=new JSONArray();historyEnd=-1;newerHistory.clear();selectedRoom="";}
    private void logoutDialog(){secureDialog(new AlertDialog.Builder(this).setTitle("로그아웃할까요?").setMessage("다시 로그인하려면 계정 ID와 복구키가 필요해요. 연결 실패 시 서버 세션 만료 전까지 서버 쪽 세션이 남을 수 있어요.").setNegativeButton("취소",null).setPositiveButton("로그아웃",(d,w)->{
        AppSessionStore.Session old=session;if(old==null)return;
        // Local logout must not depend on a cancellable network callback or foreground state.
        clearSession();error="기기에서 로그아웃했어요. 서버 세션 해지는 아직 확인되지 않았어요.";publish("welcome");
        deleteStoredSession();
        api.request("DELETE","/v1/auth/session",null,old.sessionToken,old.deviceToken,old.serverOnion,(status,body,code)->{
            if(destroyed||session!=null||storageBlocked)return;boolean revoked=status>=200&&status<300&&code.isEmpty();
            error=revoked?"기기와 서버에서 로그아웃했어요.":"기기에서 로그아웃했어요. 서버 세션 해지는 확인하지 못했어요.";publish(null);
        });
    }).create());}
    private void refreshRooms(){if(session==null||!ready())return;long operation=flow.begin();if(operation<0)return;publish(null);loadRoomPage(operation,"",new JSONArray(),new HashSet<>());}
    private void loadRoomPage(long operation,String cursor,JSONArray accumulated,Set<String> cursors){String method=cursor.isEmpty()?"GET":"POST",path=cursor.isEmpty()?"/v1/rooms":"/v1/rooms/page";request(method,path,cursor.isEmpty()?null:object("after_room_id",cursor),(status,body,code)->{if(!flow.owns(operation))return;if(status<200||status>=300||!code.isEmpty()){if(flow.complete(operation))failed(status,code);return;}JSONArray page=body.optJSONArray("rooms");if(page==null||page.length()>64||accumulated.length()+page.length()>512){if(flow.complete(operation))failed(0,"INVALID_ROOM_PAGE");return;}for(int i=0;i<page.length();i++)accumulated.put(page.opt(i));if(!(body.opt("next_cursor") instanceof String)){if(flow.complete(operation))failed(0,"INVALID_ROOM_CURSOR");return;}String next=body.optString("next_cursor","");if(next.isEmpty()){if(!flow.complete(operation))return;try{rooms=projectRooms(accumulated);publish(null);}catch(IllegalArgumentException invalid){failed(0,"INVALID_ROOM_PAGE");}return;}if(!UiCommandPolicy.validOpaqueId(next)||page.length()!=64||accumulated.length()>=512||next.compareTo(cursor)<=0||page.optJSONObject(page.length()-1)==null||!next.equals(page.optJSONObject(page.length()-1).optString("room_id"))||!cursors.add(next)||cursors.size()>=8){if(flow.complete(operation))failed(0,"INVALID_ROOM_CURSOR");return;}loadRoomPage(operation,next,accumulated,cursors);});}
    private JSONArray projectRooms(JSONArray source){return NativeResponseRules.rooms(source,session.accountId);}
    private void updateMessages(JSONArray next){messages=next;if(historyEnd>messages.length()){historyEnd=-1;newerHistory.clear();}}
    private void request(String method,String path,JSONObject body,TorApiClient.Callback callback){AppSessionStore.Session current=session;if(current==null)return;api.request(method,path,body,current.sessionToken,current.deviceToken,current.serverOnion,callback);}
    private boolean expireFromCoordinator(String code){if(!CoordinatorFailures.expiresSession(code))return false;error="로그인이 만료되었어요. 복구키로 다시 로그인해주세요. 기기의 암호화된 대화와 전송 대기는 보관해요.";clearSession();deleteStoredSession();publish("welcome");return true;}
    private void failed(int status,String code){error=status==401?"로그인이 만료되었거나 계정 정보를 확인할 수 없어요.":status==429?"요청이 많아요. 잠시 후 다시 시도해주세요.":status==409?"현재 상태에서 요청을 처리할 수 없어요.":"연결을 완료하지 못했어요. 설정에서 연결 상태를 확인하고 다시 시도해주세요.";if(status==401&&session!=null){expireFromCoordinator("AUTH_REQUIRED");}else publish(null);}
    private String id(){return "demo-"+UUID.randomUUID().toString();}
    private void createInviteDialog(){createInviteDialog("");}
    private void createInviteDialog(String suggestedAccount){
        if(session==null)return;final String owner=session.accountId,origin=session.serverOnion;
        LinearLayout fields=form();EditText target=field(fields,"상대방 계정 ID",false);target.setText(suggestedAccount);TextView hint=new TextView(this);fields.addView(hint);
        AlertDialog box=new AlertDialog.Builder(this).setTitle("초대장 만들기").setView(scrollForm(fields)).setNegativeButton("취소",null).setPositiveButton("만들기",null).create();secureDialog(box);
        box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(session==null||!owner.equals(session.accountId)||!origin.equals(session.serverOnion)){box.dismiss();return;}
            String peer=target.getText().toString().trim();if(!UiCommandPolicy.validOpaqueId(peer)||owner.equals(peer)){target.setError("상대방 계정 ID를 확인해주세요.");return;}
            if(!ready()){hint.setText(connectionLabel());return;}
            long operation=flow.begin();if(operation<0)return;box.dismiss();publish(null);
            request("POST","/v1/invitations",object("invitation_id",id(),"recipient_id",peer),(status,body,code)->{if(!flow.complete(operation))return;
                if(status<200||status>=300||!code.isEmpty()){failed(status,code);return;}
                JSONObject invitation=body.optJSONObject("invitation");String token=body.optString("invitation_token");
                if(invitation==null||token.isEmpty()){failed(0,"INVALID_RESPONSE");return;}
                noticeWithCopy("초대 코드","초대한 상대방에게만 전달해주세요.\n\n"+invitation.optString("invitation_id")+"\n"+token,InvitationInput.copyValue(session.serverOnion,invitation.optString("invitation_id"),token));publish(null);
            });
        });
    }

    private void acceptInviteDialog(){LinearLayout fields=form();EditText id=field(fields,"복사한 초대 정보 또는 초대 ID",false),token=field(fields,"코드 (나누어 받은 경우)",true);id.setSingleLine(false);id.setMaxLines(4);secureDialog(new AlertDialog.Builder(this).setTitle("초대 코드 입력").setView(scrollForm(fields)).setNegativeButton("취소",null).setPositiveButton("초대 수락",(d,w)->{InvitationInput parsed;try{parsed=InvitationInput.parse(id.getText().toString(),token.getText().toString(),session.serverOnion);}catch(IllegalArgumentException invalid){notice("초대 확인","초대 정보와 현재 서버가 일치하는지 확인해주세요.");return;}String invite=parsed.id,secret=parsed.token;token.setText("");id.setText("");long operation=flow.begin();if(operation<0)return;publish(null);request("POST","/v1/invitations/consume",object("invitation_id",invite,"invitation_token",secret,"idempotency_key",InvitationInput.consumeKey(session.serverOnion,session.accountId,invite)),(status,body,code)->{if(!flow.complete(operation))return;if(status<200||status>=300||!code.isEmpty()){failed(status,code);return;}publish("chats");refreshRooms();});}).create());}
    private void maybeEnroll(){if(localHistoryMode||recoveryHoldsEnrollment())return;if(destroyed||messaging==null||session==null||enrolling||!foreground||controller.phase()!=TorReadinessGate.Phase.READY)return;if(enrollmentReady||enrollmentAttempted)return;enrollmentAttempted=true;enrolling=true;messaging.enroll(code->{enrolling=false;if(expireFromCoordinator(code))return;enrollmentReady=code.isEmpty();if(!code.isEmpty())error="기기 암호키 연결을 완료하지 못했어요. 설정에서 연결을 확인하고 다시 시도해주세요.";publish(null);});}
    private void openRoom(String room){if(localHistoryMode)leaveLocalHistory();String openingPeer=peerForRoom(room);if(peerBlocks==null||!peerBlocks.ready()){error=protectionMessage();publish(null);return;}if(peerBlocks.blocksSending(openingPeer)){openLocalHistory(room,openingPeer,"");return;}String expected="";for(int i=0;i<rooms.length();i++){JSONObject r=rooms.optJSONObject(i);if(r!=null&&room.equals(r.optString("room_id")))expected=r.optString("peer_id");}if(!UiCommandPolicy.validOpaqueId(expected)||!ready())return;final String expectedPeer=expected;if(!enrollmentReady){enrollmentAttempted=false;maybeEnroll();error="기기 암호키 연결을 준비하고 있어요. 잠시 후 대화를 다시 열어주세요.";publish(null);return;}selectedRoom=room;messages=new JSONArray();historyEnd=-1;newerHistory.clear();publish("chat");long operation=flow.begin();if(operation<0)return;publish(null);messaging.openRoom(room,expectedPeer,code->{if(!flow.complete(operation))return;if(expireFromCoordinator(code))return;updateMessages(messaging.messagesForRoom(selectedRoom));if(code.equals("PEER_NOT_VERIFIED")){error="상대방 암호키를 확인한 뒤 대화를 시작해주세요.";publish(null);verifyPeerDialog();}else if(!code.isEmpty()){error="대화의 암호화 연결을 준비하지 못했어요. 새로고침하여 다시 시도해주세요.";publish(null);}else{error="";publish(null);refreshMessages();}});}
    private void refreshPeerIdentity(){if(!ready())return;String expected="";for(int i=0;i<rooms.length();i++){JSONObject room=rooms.optJSONObject(i);if(room!=null&&selectedRoom.equals(room.optString("room_id")))expected=room.optString("peer_id");}if(!UiCommandPolicy.validOpaqueId(expected))return;long operation=flow.begin();if(operation<0)return;publish(null);messaging.openRoom(selectedRoom,expected,code->{if(!flow.complete(operation))return;if(expireFromCoordinator(code))return;updateMessages(messaging.messagesForRoom(selectedRoom));if(code.isEmpty()||code.equals("PEER_NOT_VERIFIED")){error="";publish(null);verifyPeerDialog();}else{error="상대방의 기기 정보를 확인하지 못했어요. 잠시 후 다시 시도해주세요.";publish(null);}});}
    private void verifyPeerDialog(){verifyPeerDialog(null);}
    private void verifyPeerDialog(ContactCard scanned){MessageCoordinator.Peer peer=messaging.pendingPeer();if(peer==null){notice("암호키 확인","확인할 상대방 암호키가 없어요. 대화를 다시 열어주세요.");return;}if(messaging.isReady(selectedRoom)){notice("확인한 상대방 암호키","계정 ID\n"+peer.accountId+"\n\n암호키 지문\n"+peer.fingerprint);return;}if(scanned!=null&&!matchesPeer(scanned,peer)){notice("QR 확인","이 대화의 상대방 암호키와 일치하지 않아요. 확인하지 않았어요.");return;}boolean replacing=!peer.oldFingerprint.isEmpty();LinearLayout fields=form();TextView fingerprint=new TextView(this);fingerprint.setText(replacing?getString(R.string.peer_identity_replacement,peer.accountId,peer.oldFingerprint,peer.fingerprint):getString(R.string.peer_identity,peer.accountId,peer.fingerprint));fingerprint.setSaveEnabled(false);fields.addView(fingerprint);if(scanned!=null){TextView matched=new TextView(this);matched.setText(R.string.contact_qr_matches_peer);fields.addView(matched);}CheckBox verified=choice(fields,replacing?"새 암호키를 직접 확인했고 이전 전송 대기 메시지는 다시 전송되지 않음을 이해했어요":"다른 경로로 상대방의 암호키와 직접 비교했어요");AlertDialog box=new AlertDialog.Builder(this).setTitle(replacing?"상대방 암호키가 바뀌었어요":"상대방 암호키 확인").setMessage(replacing?"기기 교체일 수도 있지만 다른 사람이 연결을 바꾸었을 수도 있어요. 신뢰하는 다른 경로로 새 지문을 반드시 확인해주세요. 이전 대화는 보관하고 이전 키로 암호화한 대기 메시지는 종료해요. 자동으로 새 키에 다시 암호화하지 않아요.":"서버가 보여주는 정보만으로 상대방의 신원을 확인할 수 없어요. 직접 만나거나 신뢰하는 다른 연락 수단으로 같은 지문인지 비교해주세요.").setView(scrollForm(fields)).setNegativeButton("나중에",null).setNeutralButton("QR 이미지로 비교",(d,w)->beginQrImport(peer)).setPositiveButton(replacing?"새 암호키로 변경":"일치해요",null).create();secureDialog(box);box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);verified.setOnCheckedChangeListener((v,on)->box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(on));box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{box.dismiss();long operation=flow.begin();if(operation<0)return;publish(null);MessageCoordinator.Callback completed=code->{if(!flow.complete(operation))return;if(expireFromCoordinator(code))return;error=code.isEmpty()?"":"암호키를 확인하지 못했어요. 연결을 다시 확인해주세요.";publish(null);if(code.isEmpty())refreshMessages();};if(replacing)messaging.replacePeerIdentity(peer,completed);else messaging.verifyPeer(peer,completed);});}
    private void refreshMessages(){if(localHistoryMode){openLocalHistory(selectedRoom,localHistoryPeer,localHistoryBefore);return;}if(!allowPeerTraffic())return;if(session==null||selectedRoom.isEmpty()||!ready())return;if(!messaging.isReady(selectedRoom)){openRoom(selectedRoom);return;}long operation=flow.begin();if(operation<0)return;publish(null);messaging.poll(selectedRoom,code->{if(!flow.complete(operation))return;if(expireFromCoordinator(code))return;updateMessages(messaging.messagesForRoom(selectedRoom));pollDelay=code.isEmpty()?10000:Math.min(60000,pollDelay*2);error=code.isEmpty()?"":"새 메시지를 확인하지 못했어요. 다시 시도해주세요.";publish(null);});}
    private boolean sendClosed(){for(int i=0;i<rooms.length();i++){JSONObject room=rooms.optJSONObject(i);if(room!=null&&selectedRoom.equals(room.optString("room_id"))){if(room.optBoolean("closed"))return true;String deadline=room.optString("send_until","");try{return !deadline.isEmpty()&&java.time.Instant.parse(deadline).toEpochMilli()<=System.currentTimeMillis();}catch(Exception invalid){return true;}}}return true;}
    private void sendMessage(String text,String draftId){if(!allowPeerTraffic())return;if(sendClosed()){error="이 대화의 전송 시간이 끝났어요. 새 초대로 다시 연결해주세요.";publish(null);return;}if(text.isEmpty()||selectedRoom.isEmpty()||!ready())return;try{SignalClient.checkText(text);}catch(IllegalArgumentException invalid){error="text_encoding".equals(invalid.getMessage())?"입력한 문자를 처리할 수 없어요. 메시지를 다시 입력해주세요.":"메시지가 너무 길어요. 내용을 나눠 보내주세요.";publish(null);return;}if(!messaging.isReady(selectedRoom)){error="상대방 암호키를 먼저 확인해주세요. 메시지는 전송되지 않았어요.";publish(null);return;}final DraftLedger.Entry draft;try{draft=draftLedger.bind(draftId,selectedRoom,text);}catch(IllegalArgumentException invalid){error="전송할 내용이 바뀌었어요. 다시 입력해주세요.";publish(null);return;}long operation=flow.begin();if(operation<0)return;publish(null);messaging.sendText(draft.room,draft.messageId,draft.text,()->clearDraft(draft),code->{if(!flow.complete(operation))return;if(expireFromCoordinator(code))return;updateMessages(messaging.messagesForRoom(selectedRoom));boolean staged=code.isEmpty()||code.startsWith("QUEUED_");if(staged)clearDraft(draft);error=code.isEmpty()?"":"메시지 전송을 확인하지 못했어요. 발신 상태를 확인하고 다시 시도해주세요.";publish(null);});}
    private void devicesDialog(){if(deviceRecovery!=null&&(deviceRecovery.pending()!=null||deviceRecovery.failed())){deviceRecoveryDialog();return;}if(!ready())return;long operation=flow.begin();if(operation<0)return;publish(null);messaging.ownContact((contact,code)->{if(!flow.owns(operation))return;if(expireFromCoordinator(code))return;if(!code.isEmpty()||session==null||!session.accountId.equals(contact.optString("account_id"))||!UiCommandPolicy.validOpaqueId(contact.optString("device_id"))){flow.complete(operation);publish(null);notice("기기 연결 확인","이 기기의 암호키를 아직 확인할 수 없어요. 연결을 준비한 뒤 다시 시도해주세요.");return;}String localDevice=contact.optString("device_id");request("GET","/v1/devices",null,(status,body,errorCode)->{if(!flow.complete(operation))return;if(status<200||status>=300||!errorCode.isEmpty()){failed(status,errorCode);return;}final JSONArray devices;try{devices=NativeResponseRules.devices(body.optJSONArray("devices"),session.accountId);}catch(IllegalArgumentException invalid){failed(0,"INVALID_DEVICES");return;}publish(null);if(devices.length()==0){notice("연결된 기기","등록된 기기가 없어요. 서버 연결을 다시 준비해주세요.");return;}String[] labels=new String[devices.length()];for(int i=0;i<devices.length();i++){JSONObject device=devices.optJSONObject(i);labels[i]=(device.optBoolean("revoked")?"해제됨 · ":localDevice.equals(device.optString("device_id"))?"이 기기 · ":"다른 기기 · ")+device.optString("device_id");}secureDialog(new AlertDialog.Builder(this).setTitle("연결된 기기").setItems(labels,(d,which)->{JSONObject device=devices.optJSONObject(which);if(localDevice.equals(device.optString("device_id"))){notice("현재 사용 중인 기기","현재 기기의 영구 해제는 아직 지원하지 않아요. 세션을 종료하려면 설정에서 로그아웃할 수 있어요.");return;}revokeDeviceDialog(device);}).setNegativeButton("닫기",null).create());});});}
    private void revokeDeviceDialog(JSONObject device){
        if(deviceRecovery==null||!deviceRecovery.loaded()||deviceRecovery.failed()||deviceRecovery.pending()!=null){deviceRecoveryDialog();return;}
        String target=device.optString("device_id");LinearLayout fields=form();EditText recovery=field(fields,"현재 계정 복구키를 입력해주세요",true);
        CheckBox consent=choice(fields,"복구키가 바뀌고 다른 로그인이 종료되며 연락처 암호키 확인이 필요함을 이해했어요");
        AlertDialog box=new AlertDialog.Builder(this).setTitle(R.string.device_recovery_title).setMessage(getString(R.string.device_recovery_confirm,target,device.optString("fingerprint"))).setView(scrollForm(fields)).setNegativeButton("취소",(d,w)->recovery.setText("")).setPositiveButton("복구키 교체하고 기기 해제",null).create();secureDialog(box);
        box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);consent.setOnCheckedChangeListener((v,on)->box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(on));
        box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String token=recovery.getText().toString().trim();if(!SessionSchema.token(token,"gd_acct_")){recovery.setError("복구키를 확인해주세요");return;}if(!ready())return;recovery.setText("");box.dismiss();prepareManagement();deviceRecovery.updateContext(api.configuredOnion(),session);deviceRecovery.start(target,token,this::recoveryFinished);publish(null);});
    }
    private boolean recoveryHoldsEnrollment(){
        if(deviceRecovery==null)return false;if(!deviceRecovery.loaded()||deviceRecovery.failed()||deviceRecovery.busy())return true;
        PendingDeviceRecoveryStore.Pending pending=deviceRecovery.pending();return pending!=null&&session!=null&&pending.serverOnion.equals(session.serverOnion)&&pending.accountId.equals(session.accountId);
    }
    private void installRecoverySession(AppSessionStore.Session value){
        AppSessionStore.Session previous=session;PendingDeviceRecoveryStore.Pending pending=deviceRecovery.pending();
        if(previous!=null&&previous.serverOnion.equals(value.serverOnion)&&previous.accountId.equals(value.accountId)&&pending!=null&&!previous.deviceId.equals(pending.targetDeviceId))
            value=new AppSessionStore.Session(value.serverOnion,value.accountId,"",previous.deviceId,previous.deviceToken,value.sessionToken);
        session=value;flow.authenticated=true;messaging.bindSession(value);peerBlocks.bindSession(value);deviceRecovery.updateContext(api.configuredOnion(),value);
        rooms=new JSONArray();messages=new JSONArray();selectedRoom="";historyEnd=-1;newerHistory.clear();localHistoryMode=false;localHistoryPeer="";localHistoryBefore="";enrolling=false;enrollmentReady=false;enrollmentAttempted=false;
        if(persistSession)saveSession();publish(null);
    }
    private void recoveryFinished(String code){
        error=code.isEmpty()?getString(R.string.device_recovery_record_key):getString(R.string.device_recovery_uncertain);publish(null);
        if(foreground&&!destroyed)deviceRecoveryDialog();
    }
    private void deviceRecoveryDialog(){
        if(deviceRecovery==null)return;deviceRecovery.updateContext(api.configuredOnion(),session);
        if(deviceRecovery.busy()){notice("기기 복구 확인","암호화된 복구 기록을 저장하거나 서버 응답을 확인하고 있어요.");return;}
        if(deviceRecovery.failed()){
            secureDialog(new AlertDialog.Builder(this).setTitle("기기 복구 기록 확인").setMessage(R.string.device_recovery_storage_error).setNegativeButton("닫기",null).setNeutralButton("다시 읽기",(d,w)->deviceRecovery.load(code->{if(foreground)deviceRecoveryDialog();})).setPositiveButton("기록 복구 옵션",(d,w)->abandonDeviceRecoveryDialog()).create());return;
        }
        if(!deviceRecovery.loaded()){notice("기기 복구 확인","저장된 복구 기록을 확인하고 있어요.");return;}
        PendingDeviceRecoveryStore.Pending pending=deviceRecovery.pending();if(pending==null){notice("기기 복구 확인","기기에 보관 중인 기기 복구 요청이 없어요.");return;}
        if(!pending.serverOnion.equals(api.configuredOnion())||session!=null&&!pending.accountId.equals(session.accountId)){
            notice("기기 복구 확인",getString(R.string.device_recovery_other_context,pending.serverOnion,pending.accountId));return;
        }
        if(pending.confirmed){showDeviceRecoveryKey(pending);return;}
        secureDialog(new AlertDialog.Builder(this).setTitle("중단된 기기 복구").setMessage(R.string.device_recovery_uncertain).setNegativeButton("나중에",null).setNeutralButton("기록 보관 옵션",(d,w)->abandonDeviceRecoveryDialog()).setPositiveButton("같은 요청 다시 확인",(d,w)->{if(!ready())return;prepareManagement();deviceRecovery.resume(this::recoveryFinished);publish(null);}).create());
    }
    private void showDeviceRecoveryKey(PendingDeviceRecoveryStore.Pending pending){
        LinearLayout fields=form();TextView key=new TextView(this);key.setText(getString(R.string.recovery_credentials,pending.accountId,pending.replacementAccountToken));key.setSaveEnabled(false);key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);fields.addView(key);
        CheckBox recorded=choice(fields,"새 복구키를 안전한 곳에 기록했어요");AlertDialog box=new AlertDialog.Builder(this).setTitle("새 복구키를 보관해주세요").setMessage(R.string.device_recovery_new_key).setView(scrollForm(fields)).setCancelable(false).setNeutralButton("새 복구 정보 복사",null).setPositiveButton("기록 완료",null).create();secureDialog(box);
        box.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->copyExplicit("Tor톡 새 복구 정보",getString(R.string.recovery_credentials,pending.accountId,pending.replacementAccountToken)));
        box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);recorded.setOnCheckedChangeListener((v,on)->box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(on));
        box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);deviceRecovery.acknowledge(pending.recoveryId,code->{if(!code.isEmpty()){error=getString(R.string.device_recovery_storage_error);key.setText("");box.dismiss();publish(null);if(foreground)deviceRecoveryDialog();return;}key.setText("");box.dismiss();enrollmentAttempted=false;enrollmentReady=false;error="새 복구키 기록을 확인했어요. 이 기기의 암호키 연결을 다시 준비해주세요.";maybeEnroll();publish(session==null?"welcome":"chats");});});
    }
    private void abandonDeviceRecoveryDialog(){
        PendingDeviceRecoveryStore.Pending pending=deviceRecovery.pending();if(pending!=null&&(!pending.serverOnion.equals(api.configuredOnion())||session!=null&&!pending.accountId.equals(session.accountId))){deviceRecoveryDialog();return;}LinearLayout fields=form();
        if(pending!=null){TextView keys=new TextView(this);keys.setText(getString(R.string.device_recovery_both_keys,pending.accountId,pending.oldAccountToken,pending.replacementAccountToken));keys.setSaveEnabled(false);keys.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);fields.addView(keys);}
        CheckBox warned=choice(fields,"복구키를 따로 보관했고 기록 삭제로 계정 접근을 잃을 수 있음을 이해했어요");
        AlertDialog box=new AlertDialog.Builder(this).setTitle("기기 복구 기록 삭제").setMessage(R.string.device_recovery_abandon).setView(scrollForm(fields)).setNegativeButton("취소",null).setPositiveButton("위험을 이해하고 기록 삭제",null).create();secureDialog(box);box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);warned.setOnCheckedChangeListener((v,on)->box.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(on));
        box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{box.dismiss();deviceRecovery.resetAfterConfirmation(code->{error=code.isEmpty()?"기기의 복구 요청 기록을 삭제했어요. 서버에서의 처리 결과는 바뀌지 않아요.":getString(R.string.device_recovery_storage_error);publish(null);});});
    }

    private String peerForRoom(String room){if(localHistoryMode&&room.equals(selectedRoom))return localHistoryPeer;for(int i=0;i<rooms.length();i++){JSONObject value=rooms.optJSONObject(i);if(value!=null&&room.equals(value.optString("room_id")))return value.optString("peer_id");}return "";}
    private String protectionMessage(){
        if(session==null||peerBlocks==null)return "";if(!peerBlocks.ready())return peerBlocks.failed()?getString(R.string.block_storage_failed):getString(R.string.block_storage_loading);
        String peer=peerForRoom(selectedRoom);if(peer.isEmpty())return "";PeerBlockJournal.Pending intent=peerBlocks.pendingFor(peer);
        if(intent!=null&&intent.blocked)return getString(R.string.block_pending_send_hold);
        PeerBlockState state=peerBlocks.known(peer);return state!=null&&state.blocked?getString(R.string.block_confirmed_send_hold):"";
    }
    private boolean allowPeerTraffic(){if(localHistoryMode){error=getString(R.string.local_history_read_only);publish(null);return false;}String reason=protectionMessage();if(!reason.isEmpty()){error=reason;publish(null);return false;}return true;}
    private void markKnownBlockedRooms(){if(peerBlocks==null)return;for(int i=0;i<rooms.length();i++){JSONObject room=rooms.optJSONObject(i);if(room==null)continue;PeerBlockState state=peerBlocks.known(room.optString("peer_id"));if(state!=null&&state.blocked)try{room.put("closed",true);}catch(Exception ignored){}}}
    private void peerActionsDialog(){String peer=peerForRoom(selectedRoom);if(!PeerBlockState.validId(peer))return;secureDialog(new AlertDialog.Builder(this).setTitle("상대방 확인과 관리").setItems(new String[]{"암호키 확인","내 차단 상태 확인","기기의 이전 대화"},(d,which)->{if(which==1)fetchBlockStatus(peer);else if(which==2)localRoomsDialog(peer,"");else if(localHistoryMode)notice("이전 대화 기록",getString(R.string.local_history_read_only));else if(messaging.isReady(selectedRoom))refreshPeerIdentity();else verifyPeerDialog();}).setNegativeButton("닫기",null).create());}
    private void contactActionsDialog(){
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();java.util.ArrayList<Runnable> actions=new java.util.ArrayList<>();
        labels.add("내 연락처와 QR");actions.add(this::contactDialog);labels.add("내 차단 관리");actions.add(()->blockListDialog(""));labels.add("확인 보류 중인 요청");actions.add(this::pendingBlocksDialog);labels.add("기기의 이전 대화");actions.add(()->localRoomsDialog("",""));
        if(peerBlocks.failed()){labels.add("차단 기록 복구");actions.add(this::resetBlockJournalDialog);}
        secureDialog(new AlertDialog.Builder(this).setTitle("연락처와 대화 관리").setItems(labels.toArray(new String[0]),(d,index)->actions.get(index).run()).setNegativeButton("닫기",null).create());
    }
    private void blockFailure(String code){if(expireFromCoordinator(code))return;error=code.equals("BLOCK_STATE_CHANGED")?getString(R.string.block_state_changed):code.equals("PENDING_BLOCK_CAPACITY")?getString(R.string.block_pending_capacity):code.equals("BLOCK_STORAGE_FAILED")?getString(R.string.block_storage_failed):getString(R.string.block_unconfirmed);publish(null);}
    private void prepareManagement(){flow.cancel();messaging.cancel();peerBlocks.cancel();if(deviceRecovery!=null)deviceRecovery.cancel();enrolling=false;maintaining=false;if(!enrollmentReady)enrollmentAttempted=false;}
    private void fetchBlockStatus(String peer){if(session==null||!ready())return;prepareManagement();long operation=flow.begin();if(operation<0)return;publish(null);peerBlocks.fetch(peer,(state,code)->{if(!flow.complete(operation))return;if(!code.isEmpty()){blockFailure(code);return;}publish(null);showBlockStatus(state);});}
    private void nativeAction(LinearLayout fields,int label,Runnable action){Button button=new Button(this);button.setText(label);button.setOnClickListener(v->{if(dialog!=null)dialog.dismiss();action.run();});fields.addView(button);}
    private void showBlockStatus(PeerBlockState state){
        if(session==null||state==null)return;PeerBlockJournal.Pending pending=peerBlocks.pendingFor(state.peerId);LinearLayout fields=form();TextView details=new TextView(this);
        String waiting=pending==null?"":!state.retryable(pending.mutationId,pending.expectedRevision)&&!state.acknowledges(pending.mutationId,pending.expectedRevision,pending.blocked)?getString(R.string.block_stale_pending):getString(pending.blocked?R.string.block_pending_block:R.string.block_pending_unblock);
        details.setText(getString(R.string.block_state_body,state.peerId,getString(state.blocked?R.string.block_owned_yes:R.string.block_owned_no),state.legacyHold?getString(R.string.block_legacy_hold):"",waiting));fields.addView(details);
        if(!peerBlocks.ready())nativeAction(fields,R.string.block_recover_action,this::resetBlockJournalDialog);
        else if(pending==null)nativeAction(fields,state.blocked?R.string.block_unblock_action:R.string.block_block_action,()->confirmBlockChange(state,!state.blocked));
        else {if(state.retryable(pending.mutationId,pending.expectedRevision))nativeAction(fields,R.string.block_retry_action,()->retryBlock(pending));nativeAction(fields,R.string.block_forget_action,()->forgetBlockDialog(pending));}
        nativeAction(fields,R.string.local_history_action,()->localRoomsDialog(state.peerId,""));
        secureDialog(new AlertDialog.Builder(this).setTitle("내 차단 상태").setView(scrollForm(fields)).setNegativeButton("닫기",null).create());
    }
    private void confirmBlockChange(PeerBlockState state,boolean desired){
        secureDialog(new AlertDialog.Builder(this).setTitle(desired?"상대방을 차단할까요?":"내 차단을 해제할까요?").setMessage(getString(desired?R.string.block_confirm_body:R.string.unblock_confirm_body,state.peerId)).setNegativeButton("취소",null).setPositiveButton(desired?"차단 요청":"해제 요청",(d,w)->{
            if(session==null||!ready())return;if(desired){messaging.cancel();flow.cancel();}long operation=flow.begin();if(operation<0)return;publish(null);
            peerBlocks.submit(state,desired,(confirmed,code)->{if(!flow.complete(operation))return;if(!code.isEmpty()){blockFailure(code);return;}blockConfirmed(confirmed);});
        }).create());
    }
    private void retryBlock(PeerBlockJournal.Pending pending){if(!ready())return;long operation=flow.begin();if(operation<0)return;publish(null);peerBlocks.retry(pending,(state,code)->{if(!flow.complete(operation))return;if(!code.isEmpty()){blockFailure(code);return;}blockConfirmed(state);});}
    private void blockConfirmed(PeerBlockState state){if(state==null)return;if(state.blocked){messaging.cancel();markKnownBlockedRooms();}if(localHistoryMode)leaveLocalHistory();selectedRoom="";messages=new JSONArray();error=getString(state.blocked?R.string.block_success:R.string.unblock_success);publish("chats");refreshRooms();}
    private void pendingBlocksDialog(){if(!peerBlocks.ready()){notice("차단 요청 기록",protectionMessage());return;}java.util.List<PeerBlockJournal.Pending> requests=peerBlocks.pending();if(requests.isEmpty()){notice("확인 보류 중인 요청","기기에 보관 중인 미확인 요청이 없어요.");return;}String[] labels=new String[requests.size()];for(int i=0;i<labels.length;i++){PeerBlockJournal.Pending value=requests.get(i);labels[i]=getString(R.string.block_list_row,value.peerId,getString(value.blocked?R.string.block_pending_block:R.string.block_pending_unblock));}secureDialog(new AlertDialog.Builder(this).setTitle("확인 보류 중인 요청").setItems(labels,(d,index)->fetchBlockStatus(requests.get(index).peerId)).setNegativeButton("닫기",null).create());}
    private void forgetBlockDialog(PeerBlockJournal.Pending pending){secureDialog(new AlertDialog.Builder(this).setTitle("보류 중인 기기 기록 지우기").setMessage(R.string.block_forget_body).setNegativeButton("취소",null).setPositiveButton("기록만 지우기",(d,w)->{long operation=flow.begin();if(operation<0)return;publish(null);peerBlocks.forgetAfterConfirmation(pending,code->{if(!flow.complete(operation))return;if(!code.isEmpty()){blockFailure(code);return;}publish(null);fetchBlockStatus(pending.peerId);});}).create());}
    private void resetBlockJournalDialog(){if(!peerBlocks.failed()){notice("차단 요청 기록",getString(R.string.block_storage_loading));return;}secureDialog(new AlertDialog.Builder(this).setTitle("차단 기록 복구").setMessage(R.string.block_reset_body).setNegativeButton("취소",null).setPositiveButton("위험을 이해하고 기록 삭제",(d,w)->{long operation=flow.begin();if(operation<0)return;messaging.cancel();publish(null);peerBlocks.resetAfterConfirmation(code->{if(!flow.complete(operation))return;if(!code.isEmpty()){blockFailure(code);return;}leaveLocalHistory();selectedRoom="";rooms=new JSONArray();messages=new JSONArray();publish("chats");refreshRooms();});}).create());}
    private void blockListDialog(String after){if(session==null||!ready())return;prepareManagement();long operation=flow.begin();if(operation<0)return;publish(null);peerBlocks.page(after,(page,code)->{if(!flow.complete(operation))return;if(!code.isEmpty()){blockFailure(code);return;}publish(null);AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("내 차단 관리").setNegativeButton("닫기",null).setNeutralButton("보류 요청",(d,w)->pendingBlocksDialog());
        if(page.items.isEmpty())builder.setMessage("확인할 내 차단 기록이 없어요.");else{String[] rows=new String[page.items.size()];for(int i=0;i<rows.length;i++){PeerBlockState value=page.items.get(i);rows[i]=getString(R.string.block_list_row,value.peerId,getString(value.blocked?R.string.block_owned_yes:R.string.block_owned_no));}builder.setItems(rows,(d,index)->fetchBlockStatus(page.items.get(index).peerId));}
        if(!page.next.isEmpty())builder.setPositiveButton("다음",(d,w)->blockListDialog(page.next));else if(!after.isEmpty())builder.setPositiveButton("처음",(d,w)->blockListDialog(""));secureDialog(builder.create());
    });}
    private void localRoomsDialog(String peer,String after){if(session==null)return;prepareManagement();long operation=flow.begin();if(operation<0)return;publish(null);messaging.listLocalRooms(peer,after,40,(page,code)->{if(!flow.complete(operation))return;if(expireFromCoordinator(code))return;publish(null);if(!code.isEmpty()){notice("기기의 이전 대화","이 기기에 보관된 대화를 열지 못했어요. 로그인한 계정과 기기 저장 상태를 확인해주세요.");return;}
        JSONArray local=page.optJSONArray("rooms");String next=page.optString("next_after","");if(local==null||local.length()>40){notice("기기의 이전 대화","기기 기록 목록을 확인하지 못했어요.");return;}
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("기기에 보관된 이전 대화").setNegativeButton("닫기",null);if(local.length()==0)builder.setMessage("이 기기에 보관된 대화가 없어요.");else{String[] labels=new String[local.length()];for(int i=0;i<labels.length;i++){JSONObject room=local.optJSONObject(i);labels[i]=getString(R.string.local_room_row,room.optString("peer_id"),room.optString("room_id"));}builder.setItems(labels,(d,index)->{JSONObject room=local.optJSONObject(index);openLocalHistory(room.optString("room_id"),room.optString("peer_id"),"");});}if(!next.isEmpty())builder.setPositiveButton("다음",(d,w)->localRoomsDialog(peer,next));secureDialog(builder.create());
    });}
    private void openLocalHistory(String room,String peer,String before){if(session==null||!UiCommandPolicy.validOpaqueId(room)||!UiCommandPolicy.validOpaqueId(peer))return;prepareManagement();long operation=flow.begin();if(operation<0)return;localHistoryMode=true;localHistoryPeer=peer;localHistoryBefore=before;selectedRoom=room;messages=new JSONArray();enrolling=false;enrollmentAttempted=false;maintaining=false;publish("chat");messaging.openLocalRoom(room,peer,before,200,code->{if(!flow.complete(operation))return;if(expireFromCoordinator(code))return;if(!code.isEmpty()){leaveLocalHistory();selectedRoom="";error=getString(R.string.local_history_failed);publish("chats");return;}localHistoryMode=true;localHistoryPeer=peer;localHistoryBefore=before;selectedRoom=room;newerHistory.clear();historyEnd=-1;updateMessages(messaging.messagesForRoom(room));error="";publish("chat");});}
    private void leaveLocalHistory(){if(messaging!=null)messaging.leaveLocalArchive();localHistoryMode=false;localHistoryPeer="";localHistoryBefore="";newerHistory.clear();historyEnd=-1;}
    private void contactDialog(){
        if(session==null)return;final AppSessionStore.Session owner=session;long operation=flow.begin();if(operation<0)return;publish(null);
        messaging.ownContact((contact,code)->{if(!flow.owns(operation))return;if(expireFromCoordinator(code))return;
            if(!code.isEmpty()||!contact.optBoolean("registered")){flow.complete(operation);publish(null);notice("내 연락처","기기 암호키 연결이 완료된 뒤 QR을 확인할 수 있어요.");return;}
            final ContactCard card;try{card=ContactCard.create(contact.getString("server"),contact.getString("account_id"),contact.getString("device_id"),contact.getInt("protocol_device_id"),contact.getString("identity_key_base64"));}
            catch(Exception malformed){flow.complete(operation);failed(0,"INVALID_CONTACT");return;}
            qrTask=qrWorker.submit(()->{try{Bitmap bitmap=ContactQrImage.render(card);runOnUiThread(()->{
                if(destroyed||!foreground||session!=owner||!flow.complete(operation)){bitmap.recycle();return;}publish(null);
                LinearLayout fields=form();ImageView qr=new ImageView(this);qr.setImageBitmap(bitmap);qr.setContentDescription("내 계정과 공개 암호키가 담긴 연락처 QR");qr.setAdjustViewBounds(true);fields.addView(qr,new LinearLayout.LayoutParams(-1,(int)(260*getResources().getDisplayMetrics().density)));
                TextView details=new TextView(this);details.setText(getString(R.string.contact_card_details,card.serverOnion,card.accountId,card.deviceId,card.keyDigest));details.setSaveEnabled(false);fields.addView(details);
                AlertDialog box=new AlertDialog.Builder(this).setTitle("내 연락처와 공개키").setMessage("QR에는 서버 주소, 계정·기기 ID와 공개키만 들어 있어요. 복구키는 포함하지 않아요.").setView(scrollForm(fields)).setPositiveButton("닫기",null).setNeutralButton("계정 ID 복사",(d,w)->copyExplicit("Tor톡 계정 ID",card.accountId)).setNegativeButton("QR 이미지 읽기",(d,w)->beginQrImport(null)).create();
                box.setOnDismissListener(d->{qr.setImageDrawable(null);bitmap.recycle();});secureDialog(box);
            });}catch(Exception invalid){runOnUiThread(()->{if(flow.complete(operation)){publish(null);notice("연락처 QR","QR을 만들지 못했어요. 다시 시도해주세요.");}});}});
        });
    }
    private boolean matchesPeer(ContactCard card,MessageCoordinator.Peer peer){return card!=null&&peer!=null&&session!=null&&card.serverOnion.equals(session.serverOnion)&&card.accountId.equals(peer.accountId)&&card.deviceId.equals(peer.deviceId)&&card.protocolDeviceId==peer.protocolDeviceId&&card.identityKeyBase64.equals(peer.identityKeyBase64);}
    private void beginQrImport(MessageCoordinator.Peer peer){
        if(session==null||awaitingQr)return;cancelQr();qrOrigin=session.serverOnion;qrAccount=session.accountId;qrPeer=peer;awaitingQr=true;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_LOCAL_ONLY,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivityForResult(picker,CONTACT_QR_REQUEST);}catch(android.content.ActivityNotFoundException unavailable){awaitingQr=false;notice("QR 이미지 읽기","이 기기에서 이미지 선택기를 열 수 없어요. 계정 ID를 직접 입력해주세요.");}
    }
    private void cancelQr(){qrGeneration++;awaitingQr=false;pendingQrCard=null;if(qrCancellation!=null){qrCancellation.cancel();qrCancellation=null;}if(qrTask!=null){qrTask.cancel(true);qrTask=null;}if(qrTimeout!=null){timer.removeCallbacks(qrTimeout);qrTimeout=null;}}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);if(requestCode!=CONTACT_QR_REQUEST||!awaitingQr)return;awaitingQr=false;
        if(resultCode!=RESULT_OK||data==null||data.getData()==null||session==null||!qrOrigin.equals(session.serverOnion)||!qrAccount.equals(session.accountId))return;
        final Uri selected=data.getData();final String boundOrigin=qrOrigin,boundAccount=qrAccount;final long generation=qrGeneration,operation=flow.begin();if(operation<0)return;publish(null);
        final CancellationSignal cancellation=new CancellationSignal();qrCancellation=cancellation;
        qrTimeout=()->{if(generation!=qrGeneration)return;cancellation.cancel();if(flow.complete(operation)){error="이미지를 읽는 데 시간이 오래 걸려요. 작은 PNG 또는 JPEG QR 이미지로 다시 시도해주세요.";publish(null);}};timer.postDelayed(qrTimeout,15000);
        qrTask=qrWorker.submit(()->{try{ContactCard card=ContactQrImage.read(getContentResolver(),selected,boundOrigin,cancellation);runOnUiThread(()->{
            if(destroyed||generation!=qrGeneration||!flow.complete(operation))return;timer.removeCallbacks(qrTimeout);qrTimeout=null;qrCancellation=null;
            if(session==null||!boundOrigin.equals(session.serverOnion)||!boundAccount.equals(session.accountId))return;publish(null);pendingQrCard=card;if(foreground)showImportedQr();
        });}catch(Exception malformed){runOnUiThread(()->{if(destroyed||generation!=qrGeneration||!flow.complete(operation))return;timer.removeCallbacks(qrTimeout);qrTimeout=null;qrCancellation=null;error="이 서버의 연락처 QR을 읽지 못했어요. 4MB 이하 PNG 또는 JPEG 이미지를 선택해주세요.";publish(null);});}});
    }
    private void showImportedQr(){
        ContactCard card=pendingQrCard;pendingQrCard=null;if(card==null||session==null||!card.serverOnion.equals(session.serverOnion)||!qrAccount.equals(session.accountId))return;
        if(qrPeer!=null){if(messaging.pendingPeer()!=qrPeer||!selectedRoom.equals(qrPeer.roomId)||!matchesPeer(card,qrPeer)){notice("QR 확인","선택한 QR이 현재 대화 상대방의 공개키와 일치하지 않아요. 암호키를 확인하지 않았어요.");return;}verifyPeerDialog(card);return;}
        secureDialog(new AlertDialog.Builder(this).setTitle("읽은 연락처").setMessage(getString(R.string.contact_card_details,card.serverOnion,card.accountId,card.deviceId,card.keyDigest)+"\n\nQR을 보낸 사람이 맞는지 신뢰하는 다른 경로로 확인해주세요.").setNegativeButton("닫기",null).setNeutralButton("계정 ID 복사",(d,w)->copyExplicit("Tor톡 계정 ID",card.accountId)).setPositiveButton("초대장 만들기",(d,w)->createInviteDialog(card.accountId)).create());
    }

    private void retryPending(){if(!allowPeerTraffic())return;if(sendClosed()){error=getString(R.string.local_history_read_only);publish(null);return;}if(!ready()||!messaging.isReady(selectedRoom))return;long operation=flow.begin();if(operation<0)return;publish(null);messaging.retryPending(selectedRoom,code->{if(!flow.complete(operation))return;if(expireFromCoordinator(code))return;updateMessages(messaging.messagesForRoom(selectedRoom));error=code.isEmpty()?"":"대기 중인 전송을 아직 확인하지 못했어요.";publish(null);});}
    private void clearDraft(DraftLedger.Entry draft){if(nativePort==null||!draftLedger.current(draft))return;try{nativePort.postMessage(new WebMessage(new JSONObject().put("type","clear_draft").put("draftId",draft.revision).put("text",draft.text).toString()));}catch(Exception ignored){}}
    @Override public void onStart(){super.onStart();foreground=true;timer.removeCallbacks(poller);timer.postDelayed(poller,10000);timer.removeCallbacks(maintainer);timer.postDelayed(maintainer,60000);if(controller!=null)controller.attachForeground();lastPublished="";publish(null);}
    @Override public void onResume(){super.onResume();if(pendingQrCard!=null)showImportedQr();if(!pendingRecoveryToken.isEmpty())showRecovery(pendingRecoveryAccount,pendingRecoveryToken,pendingRecoverySignedIn);else if(pendingEnrollment!=null&&(dialog==null||!dialog.isShowing()))resumePendingDialog();else if(deviceRecovery!=null&&deviceRecovery.pending()!=null&&(dialog==null||!dialog.isShowing()))deviceRecoveryDialog();}
    @Override public void onStop(){foreground=false;if(!awaitingQr)cancelQr();maintaining=false;timer.removeCallbacks(poller);timer.removeCallbacks(maintainer);if(dialog!=null){dialog.dismiss();dialog=null;}flow.cancel();enrolling=false;if(messaging!=null)messaging.cancel();if(peerBlocks!=null)peerBlocks.cancel();if(deviceRecovery!=null)deviceRecovery.cancel();if(api!=null)api.cancelAll();if(controller!=null)controller.detachForeground();super.onStop();}
    @Override public void onBackPressed(){if(!route.equals("welcome")&&!route.equals("chats")){String next=route.equals("settings")?"privacy":session!=null?"chats":"welcome";if(localHistoryMode)leaveLocalHistory();flow.cancel();peerBlocks.cancel();if(deviceRecovery!=null)deviceRecovery.cancel();enrolling=false;maintaining=false;messaging.cancel();api.cancelAll();publish(next);}else super.onBackPressed();}
    @Override public Object onRetainNonConfigurationInstance(){return new Retained(session,persistSession,route,selectedRoom,rooms,localHistoryMode,localHistoryPeer,localHistoryBefore);}
    @Override public void onDestroy(){destroyed=true;cancelQr();qrWorker.shutdownNow();bridgeDocument=null;draftDocument=null;draftLedger.reset();timer.removeCallbacksAndMessages(null);closePort();if(controller!=null)controller.removeListener(changed);if(peerBlocks!=null)peerBlocks.close();if(deviceRecovery!=null)deviceRecovery.close();if(messaging!=null)messaging.close();if(api!=null)api.close();if(web!=null){web.stopLoading();if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}super.onDestroy();}
}
