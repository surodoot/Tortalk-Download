package dev.gyeopdam.embeddedtor;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Pure, synthetic boundary tests. These do not prove an onion address checksum or ownership. */
public class OnionApiRequestTest {
    static final String HOST = "xao2lxsmia2edq2n5zxg6uahx6xox2t7bfjw6b5vdzsxi7ezmqob6qid.onion";
    static final String ORIGIN = "http://" + HOST;
    static final String OTHER_ORIGIN = "http://" + repeat('a', 56) + ".onion";

    @Test public void canonicalizesOnlyExplicitHttp80OnionForms() {
        for (String suffix : new String[]{"", "/", ":80", ":80/"}) {
            assertEquals(ORIGIN, OnionApiRequest.canonicalOrigin(ORIGIN + suffix));
            OnionApiRequest request = request(ORIGIN + suffix, "GET", "/v1/health", new byte[0], null, null, null);
            assertEquals(HOST, request.host); assertEquals(ORIGIN, request.origin);
        }
    }
    @Test public void rejectsClearnetUserInfoFragmentsQueriesPortsAndAmbiguousOrigins() {
        String[] invalid = {null, "", "https://" + HOST, "HTTP://" + HOST, "http://" + HOST.toUpperCase(),
                "http://example.com", "http://127.0.0.1", "http://localhost", "http://[::1]", "//" + HOST,
                ORIGIN + ":443", ORIGIN + ":080", ORIGIN + ":0", ORIGIN + "/v1", ORIGIN + "//",
                ORIGIN + "?x=1", ORIGIN + "#fragment", ORIGIN + ".", ORIGIN + "\n", " " + ORIGIN,
                "http://user@" + HOST, "http://user:secret@" + HOST, "http://" + repeat('a', 55) + ".onion",
                "http://" + repeat('a', 57) + ".onion", "http://" + repeat('1', 56) + ".onion",
                "http://" + repeat('a', 56) + "%2eonion", ORIGIN + "\\@example.com"};
        for (String value : invalid) {
            IllegalArgumentException error = assertThrows(String.valueOf(value), IllegalArgumentException.class,
                    () -> OnionApiRequest.canonicalOrigin(value));
            assertEquals("INVALID_ONION_ORIGIN", error.getMessage());
        }
    }
    @Test public void acceptsOnlyGetPostDeleteAndOriginRelativeBoundedApiPaths() {
        for (String method : new String[]{"GET", "POST", "DELETE"}) {
            OnionApiRequest request = request(ORIGIN, method, "/v1/devices/device_1-A", new byte[0], "", "", "");
            assertEquals(method, request.method); assertEquals("/v1/devices/device_1-A", request.path);
        }
        String longest = "/v1/" + repeat('a', 508);
        assertEquals(512, request(ORIGIN,"GET",longest,new byte[0],"","","").path.length());
        for (String method : new String[]{null,"get","PUT","PATCH","HEAD","OPTIONS","CONNECT","GET\r\nX: y"})
            assertThrows(IllegalArgumentException.class, () -> request(ORIGIN,method,"/v1/users",new byte[0],"","",""));
        for (String path : new String[]{null,"","/","/v1/","/v2/users","/v1/users/","/v1//users","/v1/../users",
                "/v1/users?limit=1","/v1/users#x","/v1/%75sers","/v1/a.b","/v1/한글","/v1/a\\b",
                "http://example.com/v1/users","//example.com/v1/users","/v1/users\r\nX: y",longest + "a"})
            assertThrows(String.valueOf(path), IllegalArgumentException.class,
                    () -> request(ORIGIN,"GET",path,new byte[0],"","",""));
    }
    @Test public void boundsRequestBytesAndProhibitsBodiesOnGetAndDelete() {
        byte[] maximum = new byte[OnionApiRequest.MAX_REQUEST_BYTES];
        assertEquals(64 * 1024, maximum.length);
        assertEquals(maximum.length, request(ORIGIN,"POST","/v1/messages",maximum,"","","").body().length);
        assertThrows(IllegalArgumentException.class,
                () -> request(ORIGIN,"POST","/v1/messages",new byte[maximum.length + 1],"","",""));
        for (String method : new String[]{"GET","DELETE"})
            assertThrows(IllegalArgumentException.class, () -> request(ORIGIN,method,"/v1/messages",new byte[]{1},"","",""));
        assertThrows(IllegalArgumentException.class, () -> request(ORIGIN,"POST","/v1/messages",null,"","",""));
    }
    @Test public void requestBodyIsDefensivelyCopiedAndCanBeDestroyed() {
        byte[] input = "synthetic-private-body".getBytes(StandardCharsets.UTF_8);
        byte[] expected = input.clone();
        OnionApiRequest request = request(ORIGIN,"POST","/v1/messages",input,"","","");
        Arrays.fill(input,(byte)0); assertArrayEquals(expected,request.body());
        byte[] output = request.body(); Arrays.fill(output,(byte)1); assertArrayEquals(expected,request.body());
        request.destroyBody(); assertArrayEquals(new byte[expected.length],request.body());
        request.destroyBody(); assertArrayEquals(new byte[expected.length],request.body());
    }
    @Test public void credentialsRequireExactlyTheirCanonicalBoundOnionOrigin() {
        for (String equivalent : new String[]{ORIGIN,ORIGIN + "/",ORIGIN + ":80",ORIGIN + ":80/"}) {
            OnionApiRequest request = request(ORIGIN,"GET","/v1/profile",new byte[0],"session-fixture","device-fixture",equivalent);
            assertEquals("session-fixture",request.sessionToken()); assertEquals("device-fixture",request.deviceToken());
        }
        for (String binding : new String[]{null,"",OTHER_ORIGIN,"http://example.com",ORIGIN + "/v1"})
            assertThrows(IllegalArgumentException.class,
                    () -> request(ORIGIN,"GET","/v1/profile",new byte[0],"session-fixture","device-fixture",binding));
        assertThrows(IllegalArgumentException.class,
                () -> request(ORIGIN,"GET","/v1/profile",new byte[0],"","device-fixture",ORIGIN));
        assertThrows(IllegalArgumentException.class,
                () -> request(OTHER_ORIGIN,"GET","/v1/profile",new byte[0],"session-fixture","",ORIGIN));
    }
    @Test public void publicRequestsNeedNoBindingAndSessionAloneIsAllowed() {
        OnionApiRequest publicRequest = request(ORIGIN,"GET","/v1/health",new byte[0],null,null,null);
        assertEquals("",publicRequest.sessionToken()); assertEquals("",publicRequest.deviceToken());
        assertEquals("session",request(ORIGIN,"GET","/v1/profile",new byte[0],"session",null,ORIGIN).sessionToken());
    }
    @Test public void tokenBoundaryRejectsHeaderInjectionWhitespaceAndNonAscii() {
        assertEquals(512,request(ORIGIN,"GET","/v1/profile",new byte[0],repeat('a',512),repeat('z',512),ORIGIN).sessionToken().length());
        for (String bad : new String[]{repeat('a',513),"x y","x\ty","x\ry","x\ny","x\u0000y","x\u007fy","é","한"}) {
            IllegalArgumentException session = assertThrows(IllegalArgumentException.class,
                    () -> request(ORIGIN,"GET","/v1/profile",new byte[0],bad,"",ORIGIN));
            assertEquals("INVALID_TOKEN",session.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> request(ORIGIN,"GET","/v1/profile",new byte[0],"session",bad,ORIGIN));
        }
    }
    @Test public void toStringNeverDisclosesOriginPathTokenOrBody() {
        OnionApiRequest request = request(ORIGIN,"POST","/v1/private_identifier",new byte[]{42},"secret-session","secret-device",ORIGIN);
        assertEquals("OnionApiRequest([redacted])",request.toString());
    }
    private static OnionApiRequest request(String origin,String method,String path,byte[] body,String session,String device,String binding) {
        return new OnionApiRequest(origin,method,path,body,session,device,binding);
    }
    private static String repeat(char c,int count) { char[] chars=new char[count]; Arrays.fill(chars,c); return new String(chars); }
}
