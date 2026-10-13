package dev.gyeopdam.embeddedtor;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A contiguous history page bounded by actual JSON expansion, not just plaintext length. */
final class HistoryWindow {
    static final int MAX_MESSAGES=40;
    static final int MAX_JSON_CHARACTERS=196608;
    final int start,end,total;
    final JSONArray items;
    private HistoryWindow(int start,int end,int total,JSONArray items){this.start=start;this.end=end;this.total=total;this.items=items;}
    static HistoryWindow of(JSONArray source,int requestedEnd){
        try {
            if(source==null)throw invalid();int total=source.length(),end=requestedEnd<0?total:Math.min(requestedEnd,total),start=end;
            int characters=2;List<JSONObject> reverse=new ArrayList<>();
            for(int i=end-1;i>=0&&reverse.size()<MAX_MESSAGES;i--) {
                JSONObject raw=source.getJSONObject(i);Object text=raw.get("text"),own=raw.get("own"),id=raw.get("id"),status=raw.get("status");
                if(!(text instanceof String)||((String)text).length()>4096||!(own instanceof Boolean)||!(id instanceof String)||!UiCommandPolicy.validOpaqueId((String)id)||!(status instanceof String)||!validStatus((String)status))throw invalid();
                JSONObject item=new JSONObject().put("id",id).put("text",text).put("own",own).put("status",status);
                int next=item.toString().length()+(reverse.isEmpty()?0:1);if(characters+next>MAX_JSON_CHARACTERS)break;
                reverse.add(item);characters+=next;start=i;
            }
            if(end>0&&reverse.isEmpty())throw invalid();JSONArray items=new JSONArray();for(int i=reverse.size()-1;i>=0;i--)items.put(reverse.get(i));
            return new HistoryWindow(start,end,total,items);
        }catch(Exception malformed){throw invalid();}
    }
    private static boolean validStatus(String status){return status.equals("server_accepted")||status.equals("pending")||status.equals("received_locally")||status.equals("retired_identity");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_HISTORY_VIEW");}
}
