package dev.gyeopdam.embeddedtor;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Only loopback mock SOCKS/HTTP peers; no public network, actual Tor, or real credentials. */
public class OnionApiTransportTest {
    private static final String HOST = OnionApiRequestTest.HOST;
    private static final String ORIGIN = OnionApiRequestTest.ORIGIN;

    @Test public void publicGetUsesDomainSocksAndExactlyAllowlistedHeaders() throws Exception {
        harness(socket -> {
            negotiate(socket,0);
            assertEquals("GET /v1/health HTTP/1.1\r\nhost: " + HOST
                    + "\r\nconnection: close\r\ncontent-length: 0\r\naccept: application/json\r\n\r\n",readHeaders(socket));
            reply(socket,"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"); assertEquals(-1,socket.getInputStream().read());
        },port -> {
            OnionApiTransport.Result result = run(port,3000,get());
            assertEquals(OnionApiTransport.Error.NONE,result.error); assertEquals(200,result.status);
            assertArrayEquals("{}".getBytes(StandardCharsets.US_ASCII),result.body);
        });
    }
    @Test public void authenticatedPostSendsExactlyOriginBoundCredentialsAndJsonBytes() throws Exception {
        byte[] payload="{\"message\":\"synthetic 한글\"}".getBytes(StandardCharsets.UTF_8);
        OnionApiRequest request=new OnionApiRequest(ORIGIN + ":80/","POST","/v1/messages",payload,
                "session-fixture","device-fixture",ORIGIN);
        harness(socket -> {
            negotiate(socket,0);
            assertEquals("POST /v1/messages HTTP/1.1\r\nhost: " + HOST + "\r\nconnection: close\r\ncontent-length: "
                    + payload.length + "\r\naccept: application/json\r\ncontent-type: application/json\r\nauthorization: Bearer session-fixture"
                    + "\r\nX-TorTalk-Device-Token: device-fixture\r\n\r\n",readHeaders(socket));
            byte[] received=new byte[payload.length]; new DataInputStream(socket.getInputStream()).readFully(received);
            assertArrayEquals(payload,received); reply(socket,"HTTP/1.1 201 Created\r\nContent-Length: 2\r\n\r\n{}");
        },port -> { assertEquals(201,run(port,3000,request).status); assertArrayEquals(new byte[payload.length],request.body()); });
    }
    @Test public void authenticatedDeleteSendsSessionWithoutInventingDeviceHeader() throws Exception {
        harness(socket -> {
            negotiate(socket,0);
            assertEquals("DELETE /v1/messages/id_1 HTTP/1.1\r\nhost: " + HOST
                    + "\r\nconnection: close\r\ncontent-length: 0\r\naccept: application/json\r\nauthorization: Bearer session-fixture\r\n\r\n",readHeaders(socket));
            reply(socket,"HTTP/1.1 204 No Content\r\n\r\n");
        },port -> assertEquals(204,run(port,3000,new OnionApiRequest(ORIGIN,"DELETE","/v1/messages/id_1",new byte[0],"session-fixture","",ORIGIN)).status));
    }
    @Test public void requestBodyAt64KiBBoundaryIsTransmittedExactly() throws Exception {
        byte[] body=new byte[OnionApiRequest.MAX_REQUEST_BYTES]; Arrays.fill(body,(byte)'x');
        OnionApiRequest request=new OnionApiRequest(ORIGIN,"POST","/v1/messages",body,"","","");
        harness(socket -> {
            negotiate(socket,0); String headers=readHeaders(socket); assertTrue(headers.contains("content-length: 65536\r\n"));
            byte[] received=new byte[body.length]; new DataInputStream(socket.getInputStream()).readFully(received); assertArrayEquals(body,received);
            reply(socket,"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        },port -> assertEquals(OnionApiTransport.Error.NONE,run(port,3000,request).error));
        assertArrayEquals(new byte[body.length],request.body()); assertEquals('x',body[0]);
    }
    @Test public void invalidRequestIsRejectedBeforeAnySocketCanBeOpened() throws Exception {
        try(ServerSocket listener=listener()) {
            listener.setSoTimeout(200);
            assertThrows(IllegalArgumentException.class,() -> new OnionApiRequest("http://example.com","POST","/v1/messages",new byte[0],"secret","",ORIGIN));
            assertThrows(IllegalArgumentException.class,() -> new OnionApiRequest(ORIGIN,"POST","/v1/messages",new byte[65537],"","",""));
            assertThrows(IllegalArgumentException.class,() -> new OnionApiRequest(ORIGIN,"GET","/v1/profile",new byte[0],"secret","",OnionApiRequestTest.OTHER_ORIGIN));
            assertThrows(SocketTimeoutException.class,listener::accept);
        }
    }
    @Test public void unofferedSocksPasswordAuthenticationNeverReceivesCredentials() throws Exception {
        harness(socket -> { greeting(socket); bytes(socket,new byte[]{5,2}); assertEquals(-1,socket.getInputStream().read()); },
                port -> assertNetwork(run(port,3000,authenticatedGet())));
    }
    @Test public void socks4DowngradeIsRejectedWithoutHttp() throws Exception {
        harness(socket -> { greeting(socket); bytes(socket,new byte[]{4,0}); assertEquals(-1,socket.getInputStream().read()); },
                port -> { OnionApiTransport.Result result=run(port,3000,authenticatedGet()); assertNotEquals(OnionApiTransport.Error.NONE,result.error); assertEquals(0,result.status); assertEquals(0,result.body.length); });
    }
    @Test public void socksAuthRefusalIsRejectedWithoutHttp() throws Exception {
        harness(socket -> { greeting(socket); bytes(socket,new byte[]{5,(byte)255}); assertEquals(-1,socket.getInputStream().read()); },
                port -> assertNetwork(run(port,3000,authenticatedGet())));
    }
    @Test public void socksConnectRejectionAndExtendedTorRejectionSendNoHttp() throws Exception {
        for(int code:new int[]{5,0xf0}) harness(socket -> { negotiate(socket,code); assertEquals(-1,socket.getInputStream().read()); },
                port -> assertNetwork(run(port,3000,authenticatedGet())));
    }
    @Test public void refusedNumericLoopbackProxyFailsWithoutDirectFallback() throws Exception {
        int port; try(ServerSocket listener=listener()) { port=listener.getLocalPort(); }
        assertNetwork(run(port,3000,authenticatedGet()));
    }
    @Test public void redirectReturnsStatusWithoutFollowingLocationOrLeakingCredentials() throws Exception {
        try(ServerSocket canary=listener()) {
            canary.setSoTimeout(200);
            harness(socket -> {
                negotiate(socket,0); readHeaders(socket);
                reply(socket,"HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + canary.getLocalPort()
                        + "/capture\r\nContent-Length: 0\r\n\r\n"); assertEquals(-1,socket.getInputStream().read());
            },port -> { OnionApiTransport.Result result=run(port,3000,authenticatedGet()); assertEquals(302,result.status); assertEquals(OnionApiTransport.Error.NONE,result.error); });
            assertThrows(SocketTimeoutException.class,canary::accept);
        }
    }
    @Test public void nonSuccessHttpStatusIsReturnedWithoutRetry() throws Exception {
        for(int status:new int[]{400,401,403,409,429,500,503}) harness(socket -> {
            negotiate(socket,0); readHeaders(socket); reply(socket,"HTTP/1.1 " + status + " Fixture\r\nContent-Length: 2\r\n\r\n{}");
        },port -> { OnionApiTransport.Result result=run(port,3000,get()); assertEquals(status,result.status); assertEquals(OnionApiTransport.Error.NONE,result.error); });
    }
    @Test public void responseAt96KiBBoundaryIsAllowed() throws Exception {
        String body=repeat('x',OnionApiRequest.MAX_RESPONSE_BYTES);
        harness(socket -> { negotiate(socket,0); readHeaders(socket); reply(socket,"HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body); },
                port -> { OnionApiTransport.Result result=run(port,3000,get()); assertEquals(OnionApiTransport.Error.NONE,result.error); assertEquals(98304,result.body.length); });
    }
    @Test public void oversizedDeclaredBodyHasBoundedFixedError() throws Exception {
        responseFailure("HTTP/1.1 200 OK\r\nContent-Length: 98305\r\n\r\n",OnionApiTransport.Error.RESPONSE_LIMIT);
    }
    @Test public void oversizedChunkedBodyHasBoundedFixedError() throws Exception {
        int size=OnionApiRequest.MAX_RESPONSE_BYTES+1;
        responseFailure("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(size)
                + "\r\n" + repeat('x',size) + "\r\n0\r\n\r\n",OnionApiTransport.Error.RESPONSE_LIMIT);
    }
    @Test public void oversizedHeadersFailClosed() throws Exception {
        responseFailure("HTTP/1.1 200 OK\r\nX-Large: " + repeat('x',9000) + "\r\nContent-Length: 0\r\n\r\n",null);
    }
    @Test public void oversizedStatusLineFailsClosed() throws Exception {
        responseFailure("HTTP/1.1 200 " + repeat('x',5000) + "\r\nContent-Length: 0\r\n\r\n",null);
    }
    @Test public void malformedAmbiguousOrOutOfRangeResponsesFailClosed() throws Exception {
        for(String response:new String[]{"HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nxx",
                "HTTP/1.1 999 Invalid\r\nContent-Length: 0\r\n\r\n", "HTTP/1.1 101 Switching Protocols\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: invalid\r\n\r\n"}) responseFailure(response,null);
    }
    @Test public void truncatedResponseNeverReturnsPartialBody() throws Exception {
        responseFailure("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nx",null);
    }
    @Test public void handshakeHasOneOverallDeadlineAndClosesSocket() throws Exception {
        harness(socket -> { greeting(socket); assertEquals(-1,socket.getInputStream().read()); },port -> {
            long began=System.nanoTime(); assertEquals(OnionApiTransport.Error.DEADLINE,run(port,400,get()).error);
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began); assertTrue(elapsed>=300 && elapsed<2500);
        });
    }
    @Test public void connectReplyDeadlineSendsNoHttp() throws Exception {
        harness(socket -> { greeting(socket); bytes(socket,new byte[]{5,0}); readConnectCommand(socket); assertEquals(-1,socket.getInputStream().read()); },
                port -> assertEquals(OnionApiTransport.Error.DEADLINE,run(port,400,authenticatedGet()).error));
    }
    @Test public void slowBodyCannotExtendOverallDeadline() throws Exception {
        harness(socket -> {
            negotiate(socket,0); readHeaders(socket); reply(socket,"HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n");
            try { for(int i=0;i<100;i++) { reply(socket,"x"); Thread.sleep(25); } }
            catch(IOException closed) { /* The bounded client closes the slow peer. */ }
        },port -> assertEquals(OnionApiTransport.Error.DEADLINE,run(port,400,get()).error));
    }
    @Test public void queuedLifetimeCountsAgainstDeadlineAndNeverConnectsAfterExpiry() throws Exception {
        try(ServerSocket listener=listener()) {
            listener.setSoTimeout(200); OnionApiTransport call=HostTorSockets.api(listener.getLocalPort(),() -> true,50);
            Thread.sleep(100); OnionApiRequest request=authenticatedGet();
            assertEquals(OnionApiTransport.Error.DEADLINE,start(call,request).get(2,TimeUnit.SECONDS).error);
            assertThrows(SocketTimeoutException.class,listener::accept);
        }
    }
    @Test public void lateSocksSuccessCannotSendHttpWhenDeadlineWorkerIsDelayed() throws Exception {
        CountDownLatch greeted=new CountDownLatch(1),workerPaused=new CountDownLatch(1),releaseWorker=new CountDownLatch(1);
        harness(socket -> {
            greeting(socket); bytes(socket,new byte[]{5,0}); readConnectCommand(socket); greeted.countDown();
            try { assertTrue(workerPaused.await(2,TimeUnit.SECONDS)); Thread.sleep(450);
                bytes(socket,new byte[]{5,0,0,1,127,0,0,1,0,1}); assertEquals(-1,socket.getInputStream().read()); }
            finally { releaseWorker.countDown(); }
        },port -> {
            OnionApiTransport call=HostTorSockets.api(port,() -> {
                if(Thread.currentThread().getName().equals("onion-api-test") && greeted.getCount()==0) {
                    workerPaused.countDown();
                    try { assertTrue(releaseWorker.await(3,TimeUnit.SECONDS)); }
                    catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
                }
                return true;
            },400);
            assertEquals(OnionApiTransport.Error.DEADLINE,start(call,authenticatedGet()).get(4,TimeUnit.SECONDS).error);
        });
    }
    @Test public void cancelDuringHandshakeClosesChannelAndDestroysOwnedBody() throws Exception {
        CountDownLatch greeted=new CountDownLatch(1);
        OnionApiRequest request=new OnionApiRequest(ORIGIN,"POST","/v1/messages",new byte[]{1,2,3},"fixture","",ORIGIN);
        harness(socket -> { greeting(socket); greeted.countDown(); assertEquals(-1,socket.getInputStream().read()); },port -> {
            OnionApiTransport call=HostTorSockets.api(port,() -> true); CompletableFuture<OnionApiTransport.Result> result=start(call,request);
            assertTrue(greeted.await(2,TimeUnit.SECONDS)); call.cancel(); assertEquals(OnionApiTransport.Error.CANCELLED,result.get(2,TimeUnit.SECONDS).error);
            assertArrayEquals(new byte[3],request.body());
        });
    }
    @Test public void cancelDuringBodyDiscardsPartialResponseAndClosesChannel() throws Exception {
        CountDownLatch bodyStarted=new CountDownLatch(1);
        harness(socket -> {
            negotiate(socket,0); readHeaders(socket); reply(socket,"HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx");
            bodyStarted.countDown(); assertEquals(-1,socket.getInputStream().read());
        },port -> {
            OnionApiTransport call=HostTorSockets.api(port,() -> true); CompletableFuture<OnionApiTransport.Result> result=start(call,get());
            assertTrue(bodyStarted.await(2,TimeUnit.SECONDS)); call.cancel(); OnionApiTransport.Result value=result.get(2,TimeUnit.SECONDS);
            assertEquals(OnionApiTransport.Error.CANCELLED,value.error); assertEquals(0,value.body.length); assertEquals(0,value.status);
        });
    }
    @Test public void readinessLossDuringHandshakeClosesChannelWithoutUiWatchdog() throws Exception {
        AtomicBoolean ready=new AtomicBoolean(true);
        harness(socket -> { greeting(socket); ready.set(false); assertEquals(-1,socket.getInputStream().read()); },
                port -> assertEquals(OnionApiTransport.Error.CANCELLED,start(HostTorSockets.api(port,ready::get),get()).get(2,TimeUnit.SECONDS).error));
    }
    @Test public void readinessLossBeforeSocksSuccessSendsNoHttp() throws Exception {
        AtomicBoolean ready=new AtomicBoolean(true);
        harness(socket -> {
            greeting(socket); bytes(socket,new byte[]{5,0}); readConnectCommand(socket); ready.set(false);
            bytes(socket,new byte[]{5,0,0,1,127,0,0,1,0,1}); assertEquals(-1,socket.getInputStream().read());
        },port -> assertEquals(OnionApiTransport.Error.CANCELLED,start(HostTorSockets.api(port,ready::get),authenticatedGet()).get(2,TimeUnit.SECONDS).error));
    }
    @Test public void readinessLossDuringBodyNeverReturnsPartialData() throws Exception {
        AtomicBoolean ready=new AtomicBoolean(true);
        harness(socket -> {
            negotiate(socket,0); readHeaders(socket); reply(socket,"HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx");
            ready.set(false); assertEquals(-1,socket.getInputStream().read());
        },port -> { OnionApiTransport.Result result=start(HostTorSockets.api(port,ready::get),get()).get(2,TimeUnit.SECONDS);
            assertEquals(OnionApiTransport.Error.CANCELLED,result.error); assertEquals(0,result.body.length); });
    }
    @Test public void cancelledOrUnreadyBeforeStartNeverConnects() throws Exception {
        try(ServerSocket listener=listener()) {
            listener.setSoTimeout(200); OnionApiTransport cancelled=HostTorSockets.api(listener.getLocalPort(),() -> true); cancelled.cancel();
            assertEquals(OnionApiTransport.Error.CANCELLED,start(cancelled,get()).get(2,TimeUnit.SECONDS).error);
            assertEquals(OnionApiTransport.Error.CANCELLED,start(HostTorSockets.api(listener.getLocalPort(),() -> false),get()).get(2,TimeUnit.SECONDS).error);
            assertThrows(SocketTimeoutException.class,listener::accept);
        }
    }
    @Test public void secondExecutionOfCompletedCallNeverOpensAnotherSocket() throws Exception {
        try(ServerSocket listener=listener()) {
            listener.setSoTimeout(200); OnionApiTransport call=HostTorSockets.api(listener.getLocalPort(),() -> false);
            assertEquals(OnionApiTransport.Error.CANCELLED,start(call,get()).get(2,TimeUnit.SECONDS).error);
            assertEquals(OnionApiTransport.Error.CANCELLED,start(call,get()).get(2,TimeUnit.SECONDS).error);
            assertThrows(SocketTimeoutException.class,listener::accept);
        }
    }
    @Test public void invalidPortsOrDeadlineLimitsAreRejected() {
        for(int port:new int[]{-1,0,65536}) assertThrows(IllegalArgumentException.class,() -> HostTorSockets.api(port,() -> true));
        for(int deadline:new int[]{-1,0,30001}) assertThrows(IllegalArgumentException.class,() -> HostTorSockets.api(9050,() -> true,deadline));
        assertEquals(30000,OnionApiTransport.DEADLINE_MS);
    }
    @Test public void resultStringNeverDisclosesPayloadOrStatusDetails() {
        OnionApiTransport.Result result=new OnionApiTransport.Result(403,"secret-payload".getBytes(StandardCharsets.UTF_8),OnionApiTransport.Error.NONE);
        assertEquals("OnionApiResult(NONE)",result.toString());
        for(OnionApiTransport.Error error:OnionApiTransport.Error.values()) {
            OnionApiTransport.Result failure=OnionApiTransport.Result.error(error);
            assertEquals(0,failure.status); assertEquals(0,failure.body.length); assertEquals("OnionApiResult(" + error.name() + ")",failure.toString());
        }
    }
    private static OnionApiRequest get() { return new OnionApiRequest(ORIGIN,"GET","/v1/health",new byte[0],"","",""); }
    private static OnionApiRequest authenticatedGet() { return new OnionApiRequest(ORIGIN,"GET","/v1/profile",new byte[0],"session-fixture","device-fixture",ORIGIN); }
    private static void assertNetwork(OnionApiTransport.Result result) { assertEquals(OnionApiTransport.Error.NETWORK,result.error); assertEquals(0,result.status); assertEquals(0,result.body.length); }
    private static OnionApiTransport.Result run(int port,int deadline,OnionApiRequest request) throws Exception {
        return start(HostTorSockets.api(port,() -> true,deadline),request).get(5,TimeUnit.SECONDS);
    }
    private static CompletableFuture<OnionApiTransport.Result> start(OnionApiTransport call,OnionApiRequest request) {
        CompletableFuture<OnionApiTransport.Result> future=new CompletableFuture<>();
        Thread worker=new Thread(() -> { try { future.complete(call.execute(request)); } catch(Throwable failure) { future.completeExceptionally(failure); } },"onion-api-test");
        worker.setDaemon(true); worker.start(); return future;
    }
    private static void responseFailure(String response,OnionApiTransport.Error expected) throws Exception {
        harness(socket -> { negotiate(socket,0); readHeaders(socket); reply(socket,response); },port -> {
            OnionApiTransport.Result result=run(port,3000,get());
            assertNotEquals(OnionApiTransport.Error.NONE,result.error);
            if(expected!=null) assertEquals(expected,result.error);
            assertEquals(0,result.status); assertEquals(0,result.body.length);
        });
    }
    private static String repeat(char c,int count) { char[] chars=new char[count]; Arrays.fill(chars,c); return new String(chars); }
    private static ServerSocket listener() throws IOException { return new ServerSocket(0,4,InetAddress.getByAddress(new byte[]{127,0,0,1})); }
    private static void greeting(Socket socket) throws IOException {
        DataInputStream input=new DataInputStream(socket.getInputStream());
        assertEquals(5,input.readUnsignedByte()); assertEquals(1,input.readUnsignedByte()); assertEquals(0,input.readUnsignedByte());
    }
    private static void negotiate(Socket socket,int status) throws IOException { greeting(socket); negotiateAfterGreeting(socket,status); }
    private static void negotiateAfterGreeting(Socket socket,int status) throws IOException {
        bytes(socket,new byte[]{5,0}); readConnectCommand(socket); bytes(socket,new byte[]{5,(byte)status,0,1,127,0,0,1,0,1});
    }
    private static void readConnectCommand(Socket socket) throws IOException {
        DataInputStream input=new DataInputStream(socket.getInputStream());
        assertEquals(5,input.readUnsignedByte()); assertEquals(1,input.readUnsignedByte()); assertEquals(0,input.readUnsignedByte());
        assertEquals(3,input.readUnsignedByte()); byte[] host=new byte[input.readUnsignedByte()]; input.readFully(host);
        assertEquals(HOST,new String(host,StandardCharsets.US_ASCII)); assertEquals(80,input.readUnsignedShort());
    }
    private static String readHeaders(Socket socket) throws IOException {
        ByteArrayOutputStream text=new ByteArrayOutputStream(); DataInputStream input=new DataInputStream(socket.getInputStream());
        while(!text.toString("US-ASCII").endsWith("\r\n\r\n")) { assertTrue(text.size()<8192); text.write(input.readUnsignedByte()); }
        return text.toString("US-ASCII");
    }
    private static void reply(Socket socket,String text) throws IOException { bytes(socket,text.getBytes(StandardCharsets.US_ASCII)); }
    private static void bytes(Socket socket,byte[] bytes) throws IOException { socket.getOutputStream().write(bytes); socket.getOutputStream().flush(); }
    private interface Server { void run(Socket socket) throws Exception; }
    private interface Client { void run(int port) throws Exception; }
    private static void harness(Server server,Client client) throws Exception {
        try(ServerSocket listener=listener()) {
            listener.setSoTimeout(5000); ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<?> peer=executor.submit(() -> {
                try(Socket socket=listener.accept()) { socket.setSoTimeout(5000); server.run(socket); }
                catch(Exception problem) { throw new RuntimeException(problem); }
            });
            try { client.run(listener.getLocalPort()); peer.get(6,TimeUnit.SECONDS); listener.setSoTimeout(100);
                assertThrows("A one-shot API exchange must not reconnect",SocketTimeoutException.class,listener::accept); }
            finally { executor.shutdownNow(); }
        }
    }
}
