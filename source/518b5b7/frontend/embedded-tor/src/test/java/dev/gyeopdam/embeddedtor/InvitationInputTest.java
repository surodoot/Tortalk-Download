package dev.gyeopdam.embeddedtor;
import org.junit.Test;
import static org.junit.Assert.*;
public final class InvitationInputTest {
 private static final String O="http://synthetic.onion",T="gd_inv_"+"a".repeat(43);
 @Test public void manualNativeFields(){InvitationInput p=InvitationInput.parse("demo-invite-1",T,O);assertEquals(T,p.token);}
 @Test public void copyRoundTrips(){InvitationInput p=InvitationInput.parse(InvitationInput.copyValue(O,"demo-invite-1",T),"",O);assertEquals("demo-invite-1",p.id);}
 @Test(expected=IllegalArgumentException.class)public void wrongServerFailsClosed(){InvitationInput.parse(InvitationInput.copyValue(O,"demo-invite-1",T),"","http://other.onion");}
 @Test(expected=IllegalArgumentException.class)public void noConflictingToken(){InvitationInput.parse(InvitationInput.copyValue(O,"demo-invite-1",T),T,O);}
 @Test(expected=IllegalArgumentException.class)public void malformedCodeRejected(){InvitationInput.parse("demo-invite-1","not-a-token",O);}
 @Test public void retryKeyStableAndAccountBound(){String one=InvitationInput.consumeKey(O,"demo-account-1","demo-invite-1");assertEquals(one,InvitationInput.consumeKey(O,"demo-account-1","demo-invite-1"));assertNotEquals(one,InvitationInput.consumeKey(O,"demo-account-2","demo-invite-1"));assertTrue(UiCommandPolicy.validOpaqueId(one));}
}
