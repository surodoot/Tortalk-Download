package dev.gyeopdam.embeddedtor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Strict actor-owned server block state. Never infers whether the other participant blocked. */
final class PeerBlockState {
    final String peerId,mutationId;
    final boolean blocked,legacyHold;
    final int revision;
    private PeerBlockState(String peer,boolean blocked,int revision,String mutation,boolean legacy){this.peerId=peer;this.blocked=blocked;this.revision=revision;this.mutationId=mutation;this.legacyHold=legacy;}
    static boolean validId(String value){return value!=null&&value.length()<=80&&value.matches("demo-[a-z0-9-]{1,75}");}
    static PeerBlockState parse(JSONObject value,String expectedPeer){
        try{
            if(value==null||value.length()!=5||!validId(expectedPeer)||!expectedPeer.equals(value.get("peer_id")))throw invalid();
            Object block=value.get("blocked"),legacy=value.get("legacy_hold"),number=value.get("revision"),mutation=value.get("mutation_id");
            if(!(block instanceof Boolean)||!(legacy instanceof Boolean)||!(number instanceof Integer||number instanceof Long)||!(mutation instanceof String))throw invalid();
            long revision=((Number)number).longValue();if(revision<0||revision>Integer.MAX_VALUE)throw invalid();
            boolean isBlocked=(Boolean)block,isLegacy=(Boolean)legacy;String id=(String)mutation;
            if(revision==0){if(!id.isEmpty()||isBlocked!=isLegacy)throw invalid();}
            else if(!validId(id)||isLegacy)throw invalid();
            return new PeerBlockState(expectedPeer,isBlocked,(int)revision,id,isLegacy);
        }catch(Exception malformed){throw invalid();}
    }
    boolean acknowledges(String id,int expectedRevision,boolean desired){return mutationId.equals(id)&&blocked==desired&&!legacyHold&&(long)revision==(long)expectedRevision+1;}
    boolean retryable(String id,int expectedRevision){return revision==expectedRevision&&!mutationId.equals(id);}
    static final class Page {final List<PeerBlockState> items;final String next;Page(List<PeerBlockState> items,String next){this.items=Collections.unmodifiableList(items);this.next=next;}}
    static Page page(JSONObject body,String after,String account){
        try{
            if(body==null||body.length()!=2||after==null||!after.isEmpty()&&!validId(after)||!validId(account))throw invalid();
            JSONArray entries=body.getJSONArray("blocks");Object cursor=body.get("next_after_peer_id");if(entries.length()>64||!(cursor instanceof String))throw invalid();
            String next=(String)cursor,previous=after;List<PeerBlockState> items=new ArrayList<>();
            for(int i=0;i<entries.length();i++){
                JSONObject entry=entries.getJSONObject(i);Object raw=entry.get("peer_id");if(!(raw instanceof String))throw invalid();String peer=(String)raw;
                if(peer.equals(account)||peer.compareTo(previous)<=0)throw invalid();items.add(parse(entry,peer));previous=peer;
            }
            if(!next.isEmpty()&&(entries.length()!=64||!validId(next)||!next.equals(previous)||next.compareTo(after)<=0))throw invalid();
            return new Page(items,next);
        }catch(Exception malformed){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_PEER_BLOCK_RESPONSE");}
}
