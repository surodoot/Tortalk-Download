package dev.gyeopdam.embeddedtor;

import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic local storage namespace tests; no Signal secrets, persistent keys, or crypto sessions. */
public class SignalProfileTest {
    @Test public void namespaceUsesStableDomainSeparatedSha256Format() {
        String namespace=SignalProfile.namespace(OnionApiRequestTest.ORIGIN,"alice-1");
        assertEquals("signal-223b6b078c5661f70b35919b67f16c5f4a6782fccac70cdce7a7e5ed528a5e61",namespace);
        assertEquals(namespace,SignalProfile.namespace(OnionApiRequestTest.ORIGIN,"alice-1"));
        assertTrue(namespace.matches("signal-[0-9a-f]{64}")); assertEquals(71,namespace.length());
        assertFalse(namespace.contains("alice-1")); assertFalse(namespace.contains(OnionApiRequestTest.HOST));
    }
    @Test public void differentAccountsAndDifferentOnionOriginsHaveDifferentStorage() {
        String alice=SignalProfile.namespace(OnionApiRequestTest.ORIGIN,"alice-1");
        String bob=SignalProfile.namespace(OnionApiRequestTest.ORIGIN,"bob-001");
        String other=SignalProfile.namespace(OnionApiRequestTest.OTHER_ORIGIN,"alice-1");
        assertNotEquals(alice,bob); assertNotEquals(alice,other); assertNotEquals(bob,other);
    }
    @Test public void acceptsOnlyAlreadyCanonicalOnionOrigins() {
        for(String origin:new String[]{null,"",OnionApiRequestTest.ORIGIN + "/",OnionApiRequestTest.ORIGIN + ":80",
                OnionApiRequestTest.ORIGIN + ":80/","https://" + OnionApiRequestTest.HOST,"http://example.com"})
            assertThrows(IllegalArgumentException.class,() -> SignalProfile.namespace(origin,"alice-1"));
    }
    @Test public void accountNameBoundaryCannotEscapeOrCollideThroughSeparators() {
        assertTrue(SignalProfile.namespace(OnionApiRequestTest.ORIGIN,"abcdef").matches("signal-[0-9a-f]{64}"));
        assertTrue(SignalProfile.namespace(OnionApiRequestTest.ORIGIN,repeat('a',80)).matches("signal-[0-9a-f]{64}"));
        for(String account:new String[]{null,"","abcde",repeat('a',81),"Alice-1","-alice1","alice_1","../alice","alice/1","alice\u00001","alice\n1","alice 1","한글계정이름"}) {
            IllegalArgumentException error=assertThrows(IllegalArgumentException.class,() -> SignalProfile.namespace(OnionApiRequestTest.ORIGIN,account));
            assertEquals("INVALID_PROFILE",error.getMessage());
        }
    }
    @Test public void identifierAndErrorsContainNoRawCredentialLikeInput() {
        String syntheticIdentifier="fixture-session-token";
        String result=SignalProfile.namespace(OnionApiRequestTest.ORIGIN,syntheticIdentifier);
        assertFalse(result.contains(syntheticIdentifier)); assertTrue(result.matches("signal-[0-9a-f]{64}"));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
                () -> SignalProfile.namespace(OnionApiRequestTest.ORIGIN,"fixture\r\nAuthorization: secret"));
        assertEquals("INVALID_PROFILE",error.getMessage());
    }
    private static String repeat(char c,int count) { char[] chars=new char[count]; java.util.Arrays.fill(chars,c); return new String(chars); }
}
