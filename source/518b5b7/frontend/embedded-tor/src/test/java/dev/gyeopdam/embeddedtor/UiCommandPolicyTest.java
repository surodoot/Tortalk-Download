package dev.gyeopdam.embeddedtor;
import org.junit.Test;
import static org.junit.Assert.*;
public final class UiCommandPolicyTest {
 @Test public void knownCommandsAllowed(){for(String s:new String[]{"ready","begin","login","setup","refresh","send_message","developer"})assertTrue(UiCommandPolicy.allows(s));}
 @Test public void rawAuthorityCommandsRejected(){for(String s:new String[]{"fetch","http","eval","export_keys","get_session","logout_all","","LOGIN"})assertFalse(UiCommandPolicy.allows(s));assertFalse(UiCommandPolicy.allows(null));}
 @Test public void routesAreAllowlisted(){assertTrue(UiCommandPolicy.validRoute("privacy"));assertFalse(UiCommandPolicy.validRoute("https://evil.test"));assertFalse(UiCommandPolicy.validRoute("file:///secret"));}
 @Test public void idsCannotInjectPathsOrMarkup(){for(String s:new String[]{"../me","demo-x?x","demo-x#x","demo-x/ack","<script>","X",""})assertFalse(UiCommandPolicy.validOpaqueId(s));assertTrue(UiCommandPolicy.validOpaqueId("demo-123456"));}
}
