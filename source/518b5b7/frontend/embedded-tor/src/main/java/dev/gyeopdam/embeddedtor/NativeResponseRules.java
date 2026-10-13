package dev.gyeopdam.embeddedtor;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validate identity bindings before any native state change or public projection to the UI. */
final class NativeResponseRules {
    static AppSessionStore.Session session(String origin,String requested,JSONObject response) {
        try {
            if(!requested.equals(string(response,"account_id")))throw invalid();
            // Recovery capabilities are never retained in the general session object.
            return new AppSessionStore.Session(origin,requested,"","","",string(response,"session_token"));
        }catch(Exception malformed){throw invalid();}
    }
    static JSONArray rooms(JSONArray source,String account) {
        try {
            if(source==null||source.length()>512||!UiCommandPolicy.validOpaqueId(account))throw invalid();
            JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();
            for(int i=0;i<source.length();i++) {
                JSONObject item=source.getJSONObject(i);String id=string(item,"room_id");
                if(!UiCommandPolicy.validOpaqueId(id)||!seen.add(id))throw invalid();
                JSONArray members=item.getJSONArray("members");if(members.length()!=2)throw invalid();
                Object first=members.get(0),second=members.get(1);
                if(!(first instanceof String)||!(second instanceof String)||first.equals(second))throw invalid();
                String a=(String)first,b=(String)second;
                if(!UiCommandPolicy.validOpaqueId(a)||!UiCommandPolicy.validOpaqueId(b)||!account.equals(a)&&!account.equals(b))throw invalid();
                String until=string(item,"send_until");if(until.length()>40)throw invalid();Instant.parse(until);
                Object closed=item.get("closed");if(!(closed instanceof Boolean))throw invalid();
                out.put(new JSONObject().put("room_id",id).put("peer_id",account.equals(a)?b:a)
                        .put("send_until",until).put("closed",closed));
            }
            return out;
        }catch(Exception malformed){throw invalid();}
    }
    static JSONArray devices(JSONArray source,String account) {
        try {
            if(source==null||source.length()>4)throw invalid();JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();
            for(int i=0;i<source.length();i++) {
                JSONObject item=source.getJSONObject(i);String id=string(item,"device_id"),owner=string(item,"account_id"),fingerprint=string(item,"key_digest");
                if(!account.equals(owner)||!UiCommandPolicy.validOpaqueId(id)||!seen.add(id)||!fingerprint.matches("[a-f0-9]{64}"))throw invalid();
                Object revoked=item.get("revoked");if(!(revoked instanceof Boolean))throw invalid();
                out.put(new JSONObject().put("device_id",id).put("account_id",owner).put("fingerprint",fingerprint).put("revoked",revoked));
            }
            return out;
        }catch(Exception malformed){throw invalid();}
    }
    private static String string(JSONObject value,String key)throws Exception {Object field=value.get(key);if(!(field instanceof String))throw invalid();return (String)field;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_SERVER_RESPONSE");}
}
