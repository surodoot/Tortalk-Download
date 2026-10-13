package dev.gyeopdam.embeddedtor;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class PeerBlockStateTest {
    private static final String PEER="demo-peer-a";
    private JSONObject state(boolean blocked,int revision,String id,boolean legacy)throws Exception{return new JSONObject().put("peer_id",PEER).put("blocked",blocked).put("revision",revision).put("mutation_id",id).put("legacy_hold",legacy);}
    @Test public void absentAndLegacyAreDifferentOwnedStates()throws Exception {PeerBlockState absent=PeerBlockState.parse(state(false,0,"",false),PEER),legacy=PeerBlockState.parse(state(true,0,"",true),PEER);assertFalse(absent.blocked);assertTrue(legacy.blocked);assertTrue(legacy.legacyHold);}
    @Test public void onlyExactAckCompletesPendingIntent()throws Exception {PeerBlockState s=PeerBlockState.parse(state(true,4,"demo-mutation-a",false),PEER);assertTrue(s.acknowledges("demo-mutation-a",3,true));assertFalse(s.acknowledges("demo-mutation-b",3,true));assertFalse(s.acknowledges("demo-mutation-a",2,true));assertFalse(s.acknowledges("demo-mutation-a",3,false));assertFalse(s.retryable("demo-mutation-b",3));}
    @Test public void retryOnlyAtOriginalRevision()throws Exception {PeerBlockState s=PeerBlockState.parse(state(false,4,"demo-mutation-a",false),PEER);assertTrue(s.retryable("demo-mutation-b",4));assertFalse(s.retryable("demo-mutation-a",4));assertFalse(s.retryable("demo-mutation-b",3));}
    @Test(expected=IllegalArgumentException.class)public void rejectsOtherPeer()throws Exception {PeerBlockState.parse(state(false,0,"",false),"demo-peer-b");}
    @Test(expected=IllegalArgumentException.class)public void rejectsPrivateExtraField()throws Exception {PeerBlockState.parse(state(false,0,"",false).put("session_token","synthetic"),PEER);}
    @Test(expected=IllegalArgumentException.class)public void rejectsCoercedBoolean()throws Exception {PeerBlockState.parse(state(false,0,"",false).put("blocked","false"),PEER);}
    @Test(expected=IllegalArgumentException.class)public void rejectsFractionalRevision()throws Exception {PeerBlockState.parse(state(false,0,"",false).put("revision",1.0),PEER);}
    @Test(expected=IllegalArgumentException.class)public void rejectsOverflow()throws Exception {PeerBlockState.parse(state(false,0,"",false).put("revision",2147483648L),PEER);}
    @Test(expected=IllegalArgumentException.class)public void rejectsFakeLegacyRevision()throws Exception {PeerBlockState.parse(state(true,1,"demo-mutation-a",true),PEER);}
    @Test(expected=IllegalArgumentException.class)public void rejectsBlockedAbsentState()throws Exception {PeerBlockState.parse(state(true,0,"",false),PEER);}
    @Test public void unblockedTombstoneRetainsRevision()throws Exception {assertEquals(3,PeerBlockState.parse(state(false,3,"demo-mutation-a",false),PEER).revision);}
    @Test public void pageReturnsOnlyOwnedRows()throws Exception {PeerBlockState.Page p=PeerBlockState.page(new JSONObject().put("blocks",new JSONArray().put(state(true,0,"",true))).put("next_after_peer_id",""),"","demo-self");assertEquals(1,p.items.size());assertEquals("",p.next);}
    @Test(expected=IllegalArgumentException.class)public void rejectsRepeatedOrRegressingCursor()throws Exception {PeerBlockState.page(new JSONObject().put("blocks",new JSONArray().put(state(false,0,"",false))).put("next_after_peer_id",PEER),PEER,"demo-self");}
    @Test(expected=IllegalArgumentException.class)public void rejectsDuplicateRows()throws Exception {PeerBlockState.page(new JSONObject().put("blocks",new JSONArray().put(state(false,0,"",false)).put(state(false,0,"",false))).put("next_after_peer_id",""),"","demo-self");}
    @Test(expected=IllegalArgumentException.class)public void rejectsSelfRow()throws Exception {PeerBlockState.page(new JSONObject().put("blocks",new JSONArray().put(state(false,0,"",false))).put("next_after_peer_id",""),"",PEER);}
}
