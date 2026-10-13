package dev.gyeopdam.embeddedtor;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class NativeResponseRulesTest {
 private static final String ORIGIN="http://"+"a".repeat(56)+".onion",ACCOUNT="demo-account-a",TOKEN="gd_sess_"+"A".repeat(43);
 private JSONObject room()throws Exception{return new JSONObject().put("room_id","demo-room-a").put("members",new JSONArray().put(ACCOUNT).put("demo-account-b")).put("send_until","2026-10-08T14:00:00Z").put("closed",false);}
 private JSONObject device()throws Exception{return new JSONObject().put("device_id","demo-device-a").put("account_id",ACCOUNT).put("key_digest","a".repeat(64)).put("revoked",false);}
 @Test public void sessionBoundAndRecoveryDropped()throws Exception {AppSessionStore.Session value=NativeResponseRules.session(ORIGIN,ACCOUNT,new JSONObject().put("account_id",ACCOUNT).put("session_token",TOKEN).put("account_token","do-not-retain"));assertEquals("",value.accountToken);assertEquals(TOKEN,value.sessionToken);}
 @Test(expected=IllegalArgumentException.class)public void wrongAccountRejected()throws Exception {NativeResponseRules.session(ORIGIN,ACCOUNT,new JSONObject().put("account_id","demo-account-other").put("session_token",TOKEN));}
 @Test(expected=IllegalArgumentException.class)public void malformedTokenRejected()throws Exception {NativeResponseRules.session(ORIGIN,ACCOUNT,new JSONObject().put("account_id",ACCOUNT).put("session_token","bad"));}
 @Test(expected=IllegalArgumentException.class)public void tokenNumberNotCoerced()throws Exception {NativeResponseRules.session(ORIGIN,ACCOUNT,new JSONObject().put("account_id",ACCOUNT).put("session_token",123));}
 @Test public void roomsExposeOnlyPublicAllowlist()throws Exception {JSONArray out=NativeResponseRules.rooms(new JSONArray().put(room().put("session_token","secret").put("account_token","secret")),ACCOUNT);assertEquals(4,out.getJSONObject(0).length());assertFalse(out.toString().contains("secret"));assertEquals("demo-account-b",out.getJSONObject(0).getString("peer_id"));}
 @Test(expected=IllegalArgumentException.class)public void roomWithoutCurrentAccountRejected()throws Exception {NativeResponseRules.rooms(new JSONArray().put(room().put("members",new JSONArray().put("demo-other-a").put("demo-other-b"))),ACCOUNT);}
 @Test(expected=IllegalArgumentException.class)public void duplicateRoomRejected()throws Exception {NativeResponseRules.rooms(new JSONArray().put(room()).put(room()),ACCOUNT);}
 @Test(expected=IllegalArgumentException.class)public void booleanStringRejected()throws Exception {NativeResponseRules.rooms(new JSONArray().put(room().put("closed","false")),ACCOUNT);}
 @Test(expected=IllegalArgumentException.class)public void invalidExpiryRejected()throws Exception {NativeResponseRules.rooms(new JSONArray().put(room().put("send_until","tomorrow")),ACCOUNT);}
 @Test public void devicePublicProjection()throws Exception {JSONArray out=NativeResponseRules.devices(new JSONArray().put(device().put("device_token","secret")),ACCOUNT);assertEquals(4,out.getJSONObject(0).length());assertFalse(out.toString().contains("secret"));}
 @Test(expected=IllegalArgumentException.class)public void anotherOwnersDeviceRejected()throws Exception {NativeResponseRules.devices(new JSONArray().put(device().put("account_id","demo-other")),ACCOUNT);}
 @Test(expected=IllegalArgumentException.class)public void duplicateDeviceRejected()throws Exception {NativeResponseRules.devices(new JSONArray().put(device()).put(device()),ACCOUNT);}
}
