package dev.gyeopdam.embeddedtor;

import android.os.Looper;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Actual TorApiClient and Netty execution; every accepted socket is synthetic numeric loopback. */
public class ApiFacadeHostTest {
    private static final String HOST="xao2lxsmia2edq2n5zxg6uahx6xox2t7bfjw6b5vdzsxi7ezmqob6qid.onion";
    private static final String ORIGIN="http://"+HOST;
    private static final String OTHER_HOST="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion";
    private static final String OTHER_ORIGIN="http://"+OTHER_HOST;
    @Before public void mainThreadFixture() { assertSame(Looper.getMainLooper(),Looper.myLooper());Looper.reset(); }

    @Test public void successfulRealHttpCallbackIsQueuedOntoMainAndDecodesUtf8Object() throws Exception {
        try(Peer peer=new Peer((socket,index) -> {
            assertEquals(HOST,negotiate(socket));String headers=readHeaders(socket);
            assertTrue(headers.startsWith("POST /v1/messages HTTP/1.1\r\n"));
            assertTrue(headers.contains("authorization: Bearer fixture-session\r\n"));
            assertTrue(headers.contains("X-TorTalk-Device-Token: fixture-device\r\n"));
            byte[] body=readBody(socket,headers);assertEquals("{\"message\":\"한글\"}",new String(body,StandardCharsets.UTF_8));
            respond(socket,201,"{\"ok\":true,\"text\":\"한글\"}".getBytes(StandardCharsets.UTF_8));
        });Fixture fixture=new Fixture(peer.port())) {
            Events events=new Events();fixture.client.request("POST","/v1/messages",new JSONObject().put("message","한글"),"fixture-session","fixture-device",ORIGIN,events);
            awaitPosts(1);assertEquals(0,events.values.size());events.await(1);
            Event event=events.only();assertEquals(201,event.status);assertEquals("",event.error);assertTrue(event.body.getBoolean("ok"));assertEquals("한글",event.body.getString("text"));
            assertSame(Thread.currentThread(),event.thread);peer.assertHealthy();
        }
    }
    @Test public void originChangeRejectsOldCredentialsAndCancelsTwoRunningTwoQueuedCalls() throws Exception {
        CountDownLatch greeted=new CountDownLatch(2);AtomicInteger httpBytes=new AtomicInteger();
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();int first=socket.getInputStream().read();if(first!=-1)httpBytes.incrementAndGet();assertEquals(-1,first); });
            Fixture fixture=new Fixture(peer.port())) {
            Events[] calls=requestFour(fixture.client);assertTrue(greeted.await(3,TimeUnit.SECONDS));
            assertTrue(fixture.client.configureOnion(OTHER_ORIGIN));
            for(Events events:calls)assertCancelledOnce(events);
            Events rejected=new Events();fixture.client.request("GET","/v1/profile",null,"fixture-session","fixture-device",ORIGIN,rejected);
            assertEquals("INVALID_REQUEST",rejected.only().error);
            awaitPosts(4);Looper.drain();peer.awaitCompleted(2);
            for(Events events:calls)assertCancelledOnce(events);
            assertEquals(2,peer.accepted.get());assertEquals(0,httpBytes.get());assertEquals(OTHER_ORIGIN,fixture.client.configuredOnion());
            peer.assertHealthy();
        }
    }
    @Test public void changingOriginNeverReusesCredentialsButNewPublicRequestUsesNewDomain() throws Exception {
        try(Peer peer=new Peer((socket,index) -> { assertEquals(OTHER_HOST,negotiate(socket));String headers=readHeaders(socket);
            assertFalse(headers.toLowerCase().contains("authorization"));assertFalse(headers.toLowerCase().contains("device-token"));respond(socket,200,"{}"); });
            Fixture fixture=new Fixture(peer.port())) {
            fixture.client.configureOnion(OTHER_ORIGIN);
            Events oldToken=new Events();fixture.client.request("GET","/v1/profile",null,"fixture-session","",ORIGIN,oldToken);
            assertEquals("INVALID_REQUEST",oldToken.only().error);Events publicCall=new Events();request(fixture.client,publicCall);publicCall.await(1);
            assertEquals("",publicCall.only().error);assertEquals(1,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void equivalentCanonicalOriginDoesNotCancelOrCreateAnotherCall() throws Exception {
        CountDownLatch greeted=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));negotiateAfterGreeting(socket);readHeaders(socket);respond(socket,200,"{}"); });
            Fixture fixture=new Fixture(peer.port())) {
            Events events=new Events();request(fixture.client,events);assertTrue(greeted.await(3,TimeUnit.SECONDS));
            assertTrue(fixture.client.configureOnion(ORIGIN+":80/"));assertEquals(ORIGIN,fixture.client.configuredOnion());assertTrue(events.values.isEmpty());
            release.countDown();events.await(1);assertEquals("",events.only().error);assertEquals(1,peer.accepted.get());peer.assertHealthy();
        } finally { release.countDown(); }
    }
    @Test public void invalidOriginDoesNotChangeConfigurationOrCancelExistingCall() throws Exception {
        CountDownLatch greeted=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));negotiateAfterGreeting(socket);readHeaders(socket);respond(socket,200,"{}"); });
            Fixture fixture=new Fixture(peer.port())) {
            Events events=new Events();request(fixture.client,events);assertTrue(greeted.await(3,TimeUnit.SECONDS));
            assertFalse(fixture.client.configureOnion("http://example.com"));assertEquals(ORIGIN,fixture.client.configuredOnion());assertTrue(events.values.isEmpty());
            release.countDown();events.await(1);assertEquals("",events.only().error);peer.assertHealthy();
        } finally { release.countDown(); }
    }
    @Test public void readinessLossCancelsQueuedAndRunningCallsWithoutNewConnections() throws Exception {
        CountDownLatch greeted=new CountDownLatch(2);
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();assertEquals(-1,socket.getInputStream().read()); });
            Fixture fixture=new Fixture(peer.port())) {
            Events[] calls=requestFour(fixture.client);assertTrue(greeted.await(3,TimeUnit.SECONDS));fixture.controller.revoke(false);
            for(Events events:calls)events.await(1);for(Events events:calls)assertCancelledOnce(events);
            peer.awaitCompleted(2);assertEquals(2,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void controllerCancellationNotificationDeliversOnceAndSuppressesWorkerCallbacks() throws Exception {
        CountDownLatch greeted=new CountDownLatch(2);
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();assertEquals(-1,socket.getInputStream().read()); });
            Fixture fixture=new Fixture(peer.port())) {
            Events[] calls=requestFour(fixture.client);assertTrue(greeted.await(3,TimeUnit.SECONDS));fixture.controller.revoke(true);
            for(Events events:calls)assertCancelledOnce(events);awaitPosts(4);Looper.drain();
            for(Events events:calls)assertCancelledOnce(events);peer.awaitCompleted(2);assertEquals(2,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void completedResponseQueuedBeforeOriginChangeCannotDeliverStaleData() throws Exception {
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,200,"{\"secret\":\"old-response\"}"); });
            Fixture fixture=new Fixture(peer.port())) {
            Events events=new Events();request(fixture.client,events);awaitPosts(1);assertTrue(events.values.isEmpty());
            fixture.client.configureOnion(OTHER_ORIGIN);assertCancelledOnce(events);Looper.drain();assertCancelledOnce(events);assertEquals(0,events.only().body.length());peer.assertHealthy();
        }
    }
    @Test public void completedResponseQueuedBeforeReadinessLossCannotDeliverStaleData() throws Exception {
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,200,"{\"secret\":\"old-response\"}"); });
            Fixture fixture=new Fixture(peer.port())) {
            Events events=new Events();request(fixture.client,events);awaitPosts(1);fixture.controller.revoke(false);events.await(1);
            assertCancelledOnce(events);assertEquals(0,events.only().body.length());peer.assertHealthy();
        }
    }
    @Test public void lostThenRecoveredReadinessDoesNotReviveOldLeaseOrCallback() throws Exception {
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,200,"{\"secret\":\"old-response\"}"); });
            Fixture fixture=new Fixture(peer.port())) {
            Events events=new Events();request(fixture.client,events);awaitPosts(1);fixture.controller.revoke(false);fixture.controller.ready(peer.port());
            events.await(1);assertCancelledOnce(events);peer.assertHealthy();
        }
    }
    @Test public void socksListenerReplacementInvalidatesAlreadyQueuedResponse() throws Exception {
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,200,"{}"); });
            Peer replacement=new Peer((socket,index) -> fail("Old call must not reconnect to replacement listener"));
            Fixture fixture=new Fixture(peer.port())) {
            Events events=new Events();request(fixture.client,events);awaitPosts(1);fixture.controller.listenerChange(replacement.port());events.await(1);
            assertCancelledOnce(events);assertEquals(0,replacement.accepted.get());peer.assertHealthy();replacement.assertHealthy();
        }
    }
    @Test public void reentrantCancellationCanRequestNewWorkWithoutRevivingOldCalls() throws Exception {
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,200,"{\"call\":"+index+"}"); });
            Fixture fixture=new Fixture(peer.port())) {
            Events old=new Events(),replacement=new Events();
            request(fixture.client,(status,body,error) -> { old.complete(status,body,error);if("CANCELLED".equals(error))request(fixture.client,replacement); });
            awaitPosts(1);fixture.client.cancelAll();assertCancelledOnce(old);replacement.await(1);Looper.drain();
            assertEquals("",replacement.only().error);assertEquals(2,replacement.only().body.getInt("call"));assertCancelledOnce(old);peer.assertHealthy();
        }
    }
    @Test public void reentrantCancelAllFromCallbackDoesNotDuplicateOtherPendingCancellations() throws Exception {
        CountDownLatch greeted=new CountDownLatch(2);
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();assertEquals(-1,socket.getInputStream().read()); });
            Fixture fixture=new Fixture(peer.port())) {
            Events first=new Events(),second=new Events();request(fixture.client,(status,body,error) -> { first.complete(status,body,error);fixture.client.cancelAll(); });
            request(fixture.client,second);assertTrue(greeted.await(3,TimeUnit.SECONDS));fixture.client.cancelAll();awaitPosts(2);Looper.drain();
            assertCancelledOnce(first);assertCancelledOnce(second);peer.assertHealthy();
        }
    }
    @Test public void reentrantClosePreventsNewCallsAndDrainsAllOldCancellationsOnce() throws Exception {
        CountDownLatch greeted=new CountDownLatch(2);
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();assertEquals(-1,socket.getInputStream().read()); });
            Fixture fixture=new Fixture(peer.port())) {
            Events first=new Events(),second=new Events(),rejected=new Events();
            request(fixture.client,(status,body,error) -> { first.complete(status,body,error);fixture.client.close();request(fixture.client,rejected); });
            request(fixture.client,second);assertTrue(greeted.await(3,TimeUnit.SECONDS));fixture.client.cancelAll();fixture.awaitShutdown();Looper.drain();
            assertCancelledOnce(first);assertCancelledOnce(second);assertEquals("CLIENT_CLOSED",rejected.only().error);assertEquals(0,fixture.controller.registrations());peer.assertHealthy();
        }
    }
    @Test public void thrownCancellationCallbackMustNotPreventOtherPendingCancellations() throws Exception {
        CountDownLatch greeted=new CountDownLatch(2);AtomicInteger first=new AtomicInteger();
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();assertEquals(-1,socket.getInputStream().read()); });
            Fixture fixture=new Fixture(peer.port())) {
            request(fixture.client,(status,body,error) -> { first.incrementAndGet();throw new IllegalStateException("SYNTHETIC_CALLBACK_FAILURE"); });
            Events[] remaining=new Events[]{new Events(),new Events(),new Events()};for(Events events:remaining)request(fixture.client,events);
            assertTrue(greeted.await(3,TimeUnit.SECONDS));Throwable propagated=null;try { fixture.client.cancelAll(); } catch(RuntimeException failure) { propagated=failure; }
            assertEquals(1,first.get());
            for(Events events:remaining)assertEquals("A throwing callback must not strand later pending cancellations; propagated="+propagated,1,events.values.size());
            for(Events events:remaining)assertCancelledOnce(events);awaitPosts(4);Looper.drain();peer.assertHealthy();
        }
    }
    @Test public void closeWithThrowingCallbackStillCancelsAllCallsUnregistersAndStopsWorkers() throws Exception {
        CountDownLatch greeted=new CountDownLatch(2);AtomicInteger first=new AtomicInteger();
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();assertEquals(-1,socket.getInputStream().read()); });
            Fixture fixture=new Fixture(peer.port())) {
            request(fixture.client,(status,body,error) -> { first.incrementAndGet();assertEquals("CANCELLED",error);throw new IllegalStateException("SYNTHETIC_CLOSE_CALLBACK_FAILURE"); });
            Events[] remaining={new Events(),new Events(),new Events()};for(Events events:remaining)request(fixture.client,events);
            assertTrue(greeted.await(3,TimeUnit.SECONDS));fixture.client.close();fixture.awaitShutdown();Looper.drain();
            assertEquals(1,first.get());for(Events events:remaining)assertCancelledOnce(events);
            assertEquals(0,fixture.controller.registrations());Events closed=new Events();request(fixture.client,closed);
            assertEquals("CLIENT_CLOSED",closed.only().error);fixture.client.close();assertEquals(1,first.get());
            peer.awaitCompleted(2);assertEquals(2,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void closeCancelsTwoRunningAndTwoQueuedCallsExactlyOnce() throws Exception {
        CountDownLatch greeted=new CountDownLatch(2);
        try(Peer peer=new Peer((socket,index) -> { greeting(socket);greeted.countDown();assertEquals(-1,socket.getInputStream().read()); });
            Fixture fixture=new Fixture(peer.port())) {
            Events[] calls=requestFour(fixture.client);assertTrue(greeted.await(3,TimeUnit.SECONDS));fixture.client.close();fixture.awaitShutdown();Looper.drain();
            for(Events events:calls)assertCancelledOnce(events);peer.awaitCompleted(2);assertEquals(2,peer.accepted.get());assertEquals(0,fixture.controller.registrations());
            Events closed=new Events();request(fixture.client,closed);assertEquals("CLIENT_CLOSED",closed.only().error);fixture.client.close();peer.assertHealthy();
        }
    }
    @Test public void pendingMainCallbacksStillCountTowardFourCallLimit() throws Exception {
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,200,"{}"); });
            Fixture fixture=new Fixture(peer.port())) {
            Events[] calls=requestFour(fixture.client);awaitPosts(4);Events busy=new Events();request(fixture.client,busy);
            assertEquals("BUSY",busy.only().error);assertEquals(4,peer.accepted.get());Looper.drain();
            for(Events events:calls)assertEquals("",events.only().error);Events next=new Events();request(fixture.client,next);next.await(1);
            assertEquals("",next.only().error);assertEquals(5,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void requestAdmissionErrorsRemainSynchronousAndOpenNoSockets() throws Exception {
        try(Peer peer=new Peer((socket,index) -> fail("Rejected request opened a socket"));Fixture fixture=new Fixture(peer.port())) {
            Events noConfig=new Events();fixture.client.configureOnion("");request(fixture.client,noConfig);assertEquals("SERVER_NOT_CONFIGURED",noConfig.only().error);
            fixture.client.configureOnion(ORIGIN);fixture.controller.revoke(false);Events unready=new Events();request(fixture.client,unready);assertEquals("TOR_NOT_READY",unready.only().error);
            fixture.controller.ready(peer.port());Events invalid=new Events();fixture.client.request("GET","/v1/health?x=1",null,"","",invalid);assertEquals("INVALID_REQUEST",invalid.only().error);
            Events unbound=new Events();fixture.client.request("GET","/v1/profile",null,"fixture-session","",unbound);assertEquals("INVALID_REQUEST",unbound.only().error);
            assertEquals(0,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void malformedOrOversizedSerializedRequestFailsBeforeNetwork() throws Exception {
        try(Peer peer=new Peer((socket,index) -> fail("Rejected request opened a socket"));Fixture fixture=new Fixture(peer.port())) {
            for(String serialized:new String[]{null,"{'a':1}","{\"a\":0} trailing","{\"a\": /*\"*/"+repeat('[',64)+"0"+repeat(']',64)+"/*\"*/}","{\"a\":\""+repeat('x',65536)+"\"}"}) {
                Events events=new Events();JSONObject body=new JSONObject(){@Override public String toString(){return serialized;}};
                fixture.client.request("POST","/v1/messages",body,"","",events);assertEquals("INVALID_REQUEST",events.only().error);
            }
            Events thrown=new Events();JSONObject body=new JSONObject(){@Override public String toString(){throw new IllegalArgumentException("fixture");}};
            fixture.client.request("POST","/v1/messages",body,"","",thrown);assertEquals("INVALID_REQUEST",thrown.only().error);
            assertEquals(0,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void strictResponseDecodingRejectsMalformedUtf8AndLenientOrNonObjectJson() throws Exception {
        List<byte[]> bad=new ArrayList<>();bad.add(new byte[]{'{','"','x','"',':','"',(byte)0xc3,0x28,'"','}'});
        for(String text:new String[]{"[]","42","null","true","\"text\"","{} {}","{'a':1}","{a:1}","{\"a\":1,}","{\"a\":NaN}","{\"a\":\"\\x\"}","\ufeff{}",
                "{\"a\": /*\"*/"+repeat('[',64)+"0"+repeat(']',64)+"/*\"*/}"})bad.add(text.getBytes(StandardCharsets.UTF_8));
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,200,bad.get(index-1)); });
            Fixture fixture=new Fixture(peer.port())) {
            for(int i=0;i<bad.size();i++){Events events=new Events();request(fixture.client,events);events.await(1);assertEquals("case "+i,"INVALID_JSON",events.only().error);assertEquals(0,events.only().body.length());}
            assertEquals(bad.size(),peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void validJsonBoundaryObjectAndEmptyNoContentResponseAreAccepted() throws Exception {
        String nested="{\"a\":"+repeat('[',31)+"0"+repeat(']',31)+"}";
        String[] bodies={"{\"numbers\":[0,-0,0.1,1e2,1E-2],\"escape\":\"\\uD83D\\uDE00\\n\\t\\\\\\\"\",\"truth\":true}",nested,""};
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,index==3?204:200,bodies[index-1]); });
            Fixture fixture=new Fixture(peer.port())) {
            for(int i=0;i<bodies.length;i++){Events events=new Events();request(fixture.client,events);events.await(1);assertEquals("",events.only().error);assertEquals(i==2?204:200,events.only().status);}
            peer.assertHealthy();
        }
    }
    @Test public void rawAmbiguousUnauthorizedResponsesCannotBecomeCleanUnauthorizedCallbacks() throws Exception {
        String[] bodies={"{\"code\":\"OTHER\",\"code\":\"UNAUTHORIZED\"}",
                "{\"code\":\"UNAUTHORIZED\",\"code\":\"OTHER\"}",
                "{\"code\":\"UNAUTHORIZED\",\"code\":\"UNAUTHORIZED\"}",
                "{\"c\\u006fde\":\"OTHER\",\"code\":\"UNAUTHORIZED\"}",
                "{\"code\":\"OTHER\",\"\\u0063ode\":\"UNAUTHORIZED\"}",
                "{\"code\":\"UNAUTHORIZED\",\"detail\":{\"reason\":0,\"reason\":1}}"};
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,401,bodies[index-1]); });
            Fixture fixture=new Fixture(peer.port())) {
            for(int i=0;i<bodies.length;i++) {
                Events events=new Events();fixture.client.request("GET","/v1/profile",null,"fixture-session","fixture-device",ORIGIN,events);events.await(1);
                Event event=events.only();assertEquals(401,event.status);assertEquals("case "+i,"INVALID_JSON",event.error);
                assertEquals(0,event.body.length());assertFalse(event.body.has("code"));
                assertFalse("Ambiguous JSON must not authorize recovery fallback",event.error.isEmpty()&&"UNAUTHORIZED".equals(event.body.opt("code")));
            }
            assertEquals(bodies.length,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void rawDuplicateRecoveryResultFieldsAreRejectedBeforeObjectConstruction() throws Exception {
        String[] bodies={"{\"status\":\"pending\",\"status\":\"recovered\",\"device_id\":\"fixture-device\",\"recovery_id\":\"fixture-recovery\"}",
                "{\"status\":\"recovered\",\"device_id\":\"other\",\"device_id\":\"fixture-device\",\"recovery_id\":\"fixture-recovery\"}",
                "{\"status\":\"recovered\",\"device_id\":\"fixture-device\",\"recovery_id\":\"other\",\"recovery_id\":\"fixture-recovery\"}",
                "{\"status\":\"pending\",\"st\\u0061tus\":\"recovered\",\"device_id\":\"fixture-device\",\"recovery_id\":\"fixture-recovery\"}",
                "{\"status\":\"recovered\",\"device_id\":\"other\",\"device_\\u0069d\":\"fixture-device\",\"recovery_id\":\"fixture-recovery\"}",
                "{\"status\":\"recovered\",\"device_id\":\"fixture-device\",\"recovery_id\":\"other\",\"recovery_\\u0069d\":\"fixture-recovery\"}",
                "{\"results\":[{\"status\":\"pending\",\"status\":\"recovered\"}]}"};
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,200,bodies[index-1]); });
            Fixture fixture=new Fixture(peer.port())) {
            for(int i=0;i<bodies.length;i++) {
                Events events=new Events();fixture.client.request("POST","/v1/devices/fixture-device/recover",null,"fixture-session","",ORIGIN,events);events.await(1);
                assertEquals(200,events.only().status);assertEquals("case "+i,"INVALID_JSON",events.only().error);assertEquals(0,events.only().body.length());
            }
            assertEquals(bodies.length,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void validUnauthorizedAndRepeatedKeysInDifferentResponseObjectsRemainAccepted() throws Exception {
        String[] bodies={"{\"code\":\"UNAUTHORIZED\"}",
                "{\"code\":\"outer\",\"child\":{\"code\":\"inner\"},\"items\":[{\"code\":1},{\"c\\u006fde\":2}],\"한글\":\"😀\"}"};
        try(Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);respond(socket,index==1?401:200,bodies[index-1]); });
            Fixture fixture=new Fixture(peer.port())) {
            Events unauthorized=new Events();request(fixture.client,unauthorized);unauthorized.await(1);
            assertEquals(401,unauthorized.only().status);assertEquals("",unauthorized.only().error);assertEquals(1,unauthorized.only().body.length());
            assertEquals("UNAUTHORIZED",unauthorized.only().body.getString("code"));
            Events nested=new Events();request(fixture.client,nested);nested.await(1);
            assertEquals(200,nested.only().status);assertEquals("",nested.only().error);
            assertEquals("inner",nested.only().body.getJSONObject("child").getString("code"));
            assertEquals(2,nested.only().body.getJSONArray("items").getJSONObject(1).getInt("code"));assertEquals("😀",nested.only().body.getString("한글"));
            assertEquals(2,peer.accepted.get());peer.assertHealthy();
        }
    }
    @Test public void redirectIsRefusedByFacadeAndNeverFollowsLocation() throws Exception {
        try(Peer canary=new Peer((socket,index) -> fail("Redirect was followed"));
            Peer peer=new Peer((socket,index) -> { negotiate(socket);readHeaders(socket);write(socket,("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:"+canary.port()+"/capture\r\nContent-Length: 2\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII)); });
            Fixture fixture=new Fixture(peer.port())) {
            Events events=new Events();request(fixture.client,events);events.await(1);assertEquals(302,events.only().status);assertEquals("REDIRECT_REFUSED",events.only().error);
            assertEquals(0,events.only().body.length());assertEquals(0,canary.accepted.get());peer.assertHealthy();canary.assertHealthy();
        }
    }
    @Test public void allFacadeMutationsRejectOffMainWithoutSideEffects() throws Exception {
        try(Peer peer=new Peer((socket,index) -> fail("Off-main request opened socket"));Fixture fixture=new Fixture(peer.port())) {
            List<Runnable> mutations=Arrays.asList(() -> fixture.client.configureOnion(OTHER_ORIGIN),() -> request(fixture.client,new Events()),fixture.client::cancelAll,fixture.client::close);
            for(Runnable mutation:mutations){CompletableFuture<Throwable> failure=new CompletableFuture<>();Thread thread=new Thread(() -> {try{mutation.run();failure.complete(null);}catch(Throwable thrown){failure.complete(thrown);}},"off-main-fixture");thread.start();
                Throwable actual=failure.get(2,TimeUnit.SECONDS);assertTrue(actual instanceof IllegalStateException);assertEquals("MAIN_THREAD_REQUIRED",actual.getMessage());thread.join();}
            assertEquals(ORIGIN,fixture.client.configuredOnion());assertEquals(1,fixture.controller.registrations());assertEquals(0,peer.accepted.get());peer.assertHealthy();
        }
    }

    private static Events[] requestFour(TorApiClient client) {
        Events[] events={new Events(),new Events(),new Events(),new Events()};for(Events item:events)client.request("GET","/v1/profile",null,"fixture-session","fixture-device",ORIGIN,item);return events;
    }
    private static void request(TorApiClient client,TorApiClient.Callback callback) {client.request("GET","/v1/health",null,"","",callback);}
    private static void assertCancelledOnce(Events events) { Event event=events.only();assertEquals("CANCELLED",event.error);assertEquals(0,event.status);assertEquals(0,event.body.length());assertSame(Thread.currentThread(),event.thread); }
    private static void awaitPosts(int count) throws Exception { await(() -> Looper.queued()>=count,"Expected "+count+" queued main callbacks"); }
    private static void await(BooleanSupplier condition,String message) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);assertTrue(message,condition.getAsBoolean());}
    private static String repeat(char c,int count) { char[] chars=new char[count];Arrays.fill(chars,c);return new String(chars); }
    private static final class Event { final int status;final JSONObject body;final String error;final Thread thread=Thread.currentThread();Event(int status,JSONObject body,String error){this.status=status;this.body=body;this.error=error;} }
    private static final class Events implements TorApiClient.Callback {
        final List<Event> values=new ArrayList<>();public void complete(int status,JSONObject body,String error){assertSame(Looper.getMainLooper(),Looper.myLooper());values.add(new Event(status,body,error));}
        Event only(){assertEquals("Callback must occur exactly once",1,values.size());return values.get(0);}
        void await(int count)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(values.size()<count&&System.nanoTime()<end)Looper.dispatchOne(25);assertEquals(count,values.size());}
    }
    private static final class Fixture implements AutoCloseable {
        final EmbeddedTorController controller=new EmbeddedTorController();final TorApiClient client;
        Fixture(int port){controller.ready(port);client=new TorApiClient(controller,HostTorSockets.FACTORY);assertTrue(client.configureOnion(ORIGIN));}
        void awaitShutdown()throws Exception {Field field=TorApiClient.class.getDeclaredField("workers");field.setAccessible(true);assertTrue("Production worker pool did not stop",((ExecutorService)field.get(client)).awaitTermination(3,TimeUnit.SECONDS));}
        public void close()throws Exception {client.close();awaitShutdown();Looper.drain();}
    }
    private interface Server { void handle(Socket socket,int index)throws Exception; }
    private static final class Peer implements AutoCloseable {
        final ServerSocket server;final ExecutorService workers=Executors.newCachedThreadPool(task->{Thread thread=new Thread(task,"synthetic-socks-peer");thread.setDaemon(true);return thread;});
        final List<Socket> sockets=new CopyOnWriteArrayList<>();final ConcurrentLinkedQueue<Throwable> failures=new ConcurrentLinkedQueue<>();
        final AtomicInteger accepted=new AtomicInteger(),completed=new AtomicInteger();volatile boolean closing;
        Peer(Server handler)throws IOException {
            server=new ServerSocket(0,8,InetAddress.getByAddress(new byte[]{127,0,0,1}));
            workers.execute(()->{while(!closing){try{Socket socket=server.accept();socket.setSoTimeout(6000);sockets.add(socket);int index=accepted.incrementAndGet();workers.execute(()->{
                try(Socket owned=socket){handler.handle(owned,index);}catch(Throwable error){if(!closing||!(error instanceof IOException))failures.add(error);}finally{completed.incrementAndGet();}
            });}catch(IOException error){if(!closing)failures.add(error);break;}}});
        }
        int port(){return server.getLocalPort();}
        void awaitCompleted(int count)throws Exception{await(()->completed.get()>=count,"Expected completed peers: "+count);assertHealthy();}
        void assertHealthy(){Throwable failure=failures.peek();if(failure!=null)throw new AssertionError("Synthetic SOCKS peer failed",failure);}
        public void close()throws Exception{closing=true;server.close();for(Socket socket:sockets)socket.close();workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));assertHealthy();}
    }
    private static void greeting(Socket socket)throws IOException{DataInputStream in=new DataInputStream(socket.getInputStream());assertEquals(5,in.readUnsignedByte());assertEquals(1,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());}
    private static String negotiate(Socket socket)throws IOException{greeting(socket);return negotiateAfterGreeting(socket);}
    private static String negotiateAfterGreeting(Socket socket)throws IOException{
        write(socket,new byte[]{5,0});DataInputStream in=new DataInputStream(socket.getInputStream());assertEquals(5,in.readUnsignedByte());assertEquals(1,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());assertEquals(3,in.readUnsignedByte());
        byte[] host=new byte[in.readUnsignedByte()];in.readFully(host);assertEquals(80,in.readUnsignedShort());write(socket,new byte[]{5,0,0,1,127,0,0,1,0,1});return new String(host,StandardCharsets.US_ASCII);
    }
    private static String readHeaders(Socket socket)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataInputStream in=new DataInputStream(socket.getInputStream());while(!bytes.toString("US-ASCII").endsWith("\r\n\r\n")){assertTrue(bytes.size()<8192);bytes.write(in.readUnsignedByte());}return bytes.toString("US-ASCII");}
    private static byte[] readBody(Socket socket,String headers)throws IOException{int length=0;for(String line:headers.split("\r\n"))if(line.startsWith("content-length: "))length=Integer.parseInt(line.substring(16));byte[] body=new byte[length];new DataInputStream(socket.getInputStream()).readFully(body);return body;}
    private static void respond(Socket socket,int status,String body)throws IOException{respond(socket,status,body.getBytes(StandardCharsets.UTF_8));}
    private static void respond(Socket socket,int status,byte[] body)throws IOException{write(socket,("HTTP/1.1 "+status+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));write(socket,body);}
    private static void write(Socket socket,byte[] bytes)throws IOException{socket.getOutputStream().write(bytes);socket.getOutputStream().flush();}
}
