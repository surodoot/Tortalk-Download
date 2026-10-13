package dev.gyeopdam.embeddedtor;
import java.util.*;
import org.json.*;
final class TorApiClient  {
    interface Callback {
        void complete(int status,JSONObject body,String error);
    }
    static final class Request  {
        final String method,path,sessionToken,deviceToken,origin;
        final JSONObject body;
        final Callback callback;
        Request(String m,String p,JSONObject b,String s,String d,String o,Callback cb) {
            method=m;
            path=p;
            body=b;
            sessionToken=s;
            deviceToken=d;
            origin=o;
            callback=cb;
        }
    }
    final List<Request> requests=new ArrayList<>();
    String origin;
    TorApiClient(String origin) {
        this.origin=origin;
    }
    String configuredOnion() {
        return origin;
    }
    void request(String method,String path,JSONObject body,String session,String device,String bound,Callback cb) {
        requests.add(new Request(method,path,body,session,device,bound,cb));
    }
    void cancelAll() {
        List<Request> copy=new ArrayList<>(requests);
        requests.clear();
        for(Request r:copy)r.callback.complete(0,new JSONObject(),"CANCELLED");
    }
    Request pop()throws Exception  {
        while(requests.isEmpty())android.os.Handler.pump();
        return requests.remove(0);
    }
}
