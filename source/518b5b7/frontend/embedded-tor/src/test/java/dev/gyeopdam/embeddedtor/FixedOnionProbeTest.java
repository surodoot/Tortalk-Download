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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Local synthetic SOCKS/HTTP peers only. No Tor, real onion connection, credentials, or signing. */
public class FixedOnionProbeTest {
    @Test public void noAuthDomainGetHasOnlyFixedHeadersAndNoIdentifiers() throws Exception {
        harness(socket -> {
            negotiate(socket, 0);
            String request = readRequest(socket);
            assertEquals("GET / HTTP/1.1\r\nhost: " + FixedOnionProbe.HOST
                    + "\r\ncontent-length: 0\r\nconnection: close\r\n\r\n", request);
            reply(socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
            assertEquals(-1, socket.getInputStream().read());
        }, port -> {
            OnionProbeSession.Result result = run(port, 3000);
            assertEquals(OnionProbeSession.State.RESPONSE, result.state);
            assertEquals(200, result.httpStatus); assertEquals(2, result.bodyBytes);
            assertEquals(OnionProbeSession.Stage.HTTP_READ, result.stage);
            assertEquals(OnionProbeSession.Reason.NONE, result.reason);
            assertTrue(result.elapsedMillis >= 0 && result.elapsedMillis < 3000);
        });
    }
    @Test public void rejectedSocksConnectFailsClosed() throws Exception {
        harness(socket -> { negotiate(socket, 5); assertEquals(-1, socket.getInputStream().read()); },
                port -> {
                    OnionProbeSession.Result result = run(port, 3000); assertFailed(result);
                    assertEquals(OnionProbeSession.Stage.ONION_CONNECT, result.stage);
                    assertEquals(OnionProbeSession.Reason.SOCKS_CONNECT_REJECTED, result.reason);
                    assertEquals(5, result.socksStatus);
                });
    }
    @Test public void unofferedPasswordAuthenticationIsRejected() throws Exception {
        harness(socket -> { greeting(socket); bytes(socket, new byte[]{5, 2}); assertEquals(-1, socket.getInputStream().read()); },
                port -> {
                    OnionProbeSession.Result result = run(port, 3000); assertFailed(result);
                    assertEquals(OnionProbeSession.Stage.SOCKS_GREETING, result.stage);
                    assertEquals("SOCKS_GREETING:SOCKS_AUTH_REJECTED:S2", result.diagnosticCode());
                });
    }
    @Test public void socks4DowngradeIsRejected() throws Exception {
        harness(socket -> { greeting(socket); bytes(socket, new byte[]{4, 0}); assertEquals(-1, socket.getInputStream().read()); },
                port -> {
                    OnionProbeSession.Result result = run(port, 3000); assertFailed(result);
                    assertEquals(OnionProbeSession.Stage.SOCKS_GREETING, result.stage);
                });
    }
    @Test public void refusedLocalProxyFailsWithoutFallback() throws Exception {
        int port;
        try (ServerSocket listener = listener()) { port = listener.getLocalPort(); }
        OnionProbeSession.Result result = run(port, 3000); assertFailed(result);
        assertEquals(OnionProbeSession.Stage.LOCAL_CONNECT, result.stage);
        assertEquals("LOCAL_CONNECT:IO_OR_PROTOCOL", result.diagnosticCode());
    }
    @Test public void redirectReturnsStatusWithoutConnectingToLocation() throws Exception {
        try (ServerSocket canary = listener()) {
            canary.setSoTimeout(200);
            harness(socket -> {
                negotiate(socket, 0); readRequest(socket);
                reply(socket, "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + canary.getLocalPort()
                        + "/private\r\nContent-Length: 0\r\n\r\n");
                assertEquals(-1, socket.getInputStream().read());
            }, port -> assertEquals(302, run(port, 3000).httpStatus));
            assertThrows(SocketTimeoutException.class, canary::accept);
        }
    }
    @Test public void oversizedDeclaredBodyFailsClosed() throws Exception {
        responseFailure("HTTP/1.1 200 OK\r\nContent-Length: " + (FixedOnionProbe.MAX_BODY_BYTES + 1) + "\r\n\r\n");
    }
    @Test public void oversizedChunkedBodyFailsClosed() throws Exception {
        responseFailure("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(FixedOnionProbe.MAX_BODY_BYTES + 1) + "\r\n"
                + repeat('x', FixedOnionProbe.MAX_BODY_BYTES + 1) + "\r\n0\r\n\r\n");
    }
    @Test public void oversizedHeadersFailClosed() throws Exception {
        responseFailure("HTTP/1.1 200 OK\r\nX-Large: " + repeat('x', 9000) + "\r\nContent-Length: 0\r\n\r\n");
    }
    @Test public void invalidOrAmbiguousHttpResponseFailsClosed() throws Exception {
        responseFailure("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nxx");
        responseFailure("HTTP/1.1 999 Invalid\r\nContent-Length: 0\r\n\r\n");
    }
    @Test public void truncatedBodyFailsClosed() throws Exception {
        responseFailure("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nx");
    }
    @Test public void handshakeDeadlineClosesSocket() throws Exception {
        harness(socket -> { greeting(socket); assertEquals(-1, socket.getInputStream().read()); }, port -> {
            long began = System.nanoTime();
            OnionProbeSession.Result result = run(port, 400);
            assertEquals(OnionProbeSession.State.TIMED_OUT, result.state);
            assertEquals(OnionProbeSession.Stage.SOCKS_GREETING, result.stage);
            assertEquals(OnionProbeSession.Reason.DEADLINE, result.reason);
            assertTrue(result.elapsedMillis >= 300 && result.elapsedMillis < 2500);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2500);
        });
    }
    @Test public void onionConnectReplyDeadlineIsDistinctFromGreetingDeadline() throws Exception {
        harness(socket -> {
            greeting(socket); bytes(socket, new byte[]{5, 0}); readConnectCommand(socket);
            assertEquals(-1, socket.getInputStream().read()); // no HTTP before CONNECT success
        }, port -> {
            OnionProbeSession.Result result = run(port, 400);
            assertEquals(OnionProbeSession.State.TIMED_OUT, result.state);
            assertEquals(OnionProbeSession.Stage.ONION_CONNECT, result.stage);
            assertEquals(OnionProbeSession.Reason.DEADLINE, result.reason);
            assertEquals(-1, result.socksStatus);
        });
    }
    @Test public void torExtendedRejectionCodeIsNumericAndNoHttpIsSent() throws Exception {
        harness(socket -> { negotiate(socket, 0xf0); assertEquals(-1, socket.getInputStream().read()); }, port -> {
            OnionProbeSession.Result result = run(port, 3000);
            assertEquals("ONION_CONNECT:SOCKS_CONNECT_REJECTED:S240", result.diagnosticCode());
        });
    }
    @Test public void progressReportsOnlyOrderedTypedStages() throws Exception {
        harness(socket -> { negotiate(socket, 0); readRequest(socket); reply(socket, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"); }, port -> {
            java.util.concurrent.ConcurrentLinkedQueue<OnionProbeSession.Stage> stages = new java.util.concurrent.ConcurrentLinkedQueue<>();
            FixedOnionProbe call = HostTorSockets.probe(port, () -> true, 3000);
            call.setProgressListener(result -> { assertEquals(OnionProbeSession.State.RUNNING, result.state); stages.add(result.stage); });
            assertEquals(OnionProbeSession.State.RESPONSE, start(call).get(4, TimeUnit.SECONDS).state);
            assertEquals(java.util.Arrays.asList(OnionProbeSession.Stage.LOCAL_CONNECT, OnionProbeSession.Stage.SOCKS_GREETING,
                    OnionProbeSession.Stage.ONION_CONNECT, OnionProbeSession.Stage.HTTP_WRITE, OnionProbeSession.Stage.HTTP_READ),
                    new java.util.ArrayList<>(stages));
        });
    }
    @Test public void overallDeadlineBoundsSlowBody() throws Exception {
        harness(socket -> {
            negotiate(socket, 0); readRequest(socket);
            reply(socket, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n");
            try { for (int i = 0; i < 100; i++) { reply(socket, "x"); Thread.sleep(25); } }
            catch (IOException closed) { /* expected cancellation of slow peer */ }
        }, port -> {
            OnionProbeSession.Result result = run(port, 400);
            assertEquals(OnionProbeSession.State.TIMED_OUT, result.state);
            assertEquals(OnionProbeSession.Stage.HTTP_READ, result.stage);
            assertEquals(OnionProbeSession.Reason.DEADLINE, result.reason);
        });
    }
    @Test public void socksNegotiationCanUseBudgetBeyondFormerTenSecondLimit() throws Exception {
        try (ServerSocket listener = listener()) {
            listener.setSoTimeout(2000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> peer = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(16000);
                    // Deliberately hold the SOCKS reply past the previous internal deadline.
                    Thread.sleep(10500);
                    negotiate(socket, 0); readRequest(socket);
                    reply(socket, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
                } catch (Exception problem) { throw new RuntimeException(problem); }
            });
            try {
                OnionProbeSession.Result result = start(HostTorSockets.probe(listener.getLocalPort(), () -> true, 15000))
                        .get(17, TimeUnit.SECONDS);
                assertEquals(OnionProbeSession.State.RESPONSE, result.state);
                assertTrue(result.elapsedMillis >= 10000 && result.elapsedMillis < 15000);
                peer.get(2, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
    }
    @Test public void diagnosticsContainOnlyFixedTokensAndNumericTime() {
        OnionProbeSession.Result result = new OnionProbeSession.Result(OnionProbeSession.State.FAILED,
                0, 0, OnionProbeSession.Stage.HTTP_READ, OnionProbeSession.Reason.INVALID_RESPONSE, -1);
        assertEquals("HTTP_READ:INVALID_RESPONSE", result.diagnosticCode());
        assertEquals(0, result.elapsedMillis);
    }
    @Test public void lateSocksSuccessCannotSendGetWhenDeadlineWorkerIsDelayed() throws Exception {
        CountDownLatch greeted = new CountDownLatch(1);
        CountDownLatch workerPaused = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        harness(socket -> {
            greeting(socket); bytes(socket, new byte[]{5,0}); readConnectCommand(socket); greeted.countDown();
            try {
                assertTrue(workerPaused.await(2, TimeUnit.SECONDS));
                Thread.sleep(450);
                bytes(socket,new byte[]{5,0,0,1,127,0,0,1,0,1});
                assertEquals(-1, socket.getInputStream().read()); // no GET after late SOCKS success
            } finally { releaseWorker.countDown(); }
        }, port -> {
            FixedOnionProbe call = HostTorSockets.probe(port, () -> {
                if (Thread.currentThread().getName().equals("onion-probe") && greeted.getCount() == 0) {
                    workerPaused.countDown();
                    try { assertTrue(releaseWorker.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
                }
                return true;
            }, 400);
            OnionProbeSession.Result result = start(call).get(4, TimeUnit.SECONDS);
            assertEquals(OnionProbeSession.State.TIMED_OUT, result.state);
            assertEquals(OnionProbeSession.Stage.ONION_CONNECT, result.stage);
        });
    }
    @Test public void responseLimitAndInvalidSyntaxHaveFixedDistinctReasons() throws Exception {
        harness(socket -> {
            negotiate(socket, 0); readRequest(socket);
            reply(socket, "HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n");
        }, port -> assertEquals(OnionProbeSession.Reason.RESPONSE_LIMIT, run(port, 3000).reason));
        harness(socket -> {
            negotiate(socket, 0); readRequest(socket);
            reply(socket, "HTTP/1.1 999 Invalid\r\nContent-Length: 0\r\n\r\n");
        }, port -> assertEquals(OnionProbeSession.Reason.INVALID_RESPONSE, run(port, 3000).reason));
    }
    @Test public void cancellationDuringHandshakeClosesChannel() throws Exception {
        CountDownLatch greeted = new CountDownLatch(1);
        harness(socket -> { greeting(socket); greeted.countDown(); assertEquals(-1, socket.getInputStream().read()); }, port -> {
            FixedOnionProbe call = HostTorSockets.probe(port, () -> true);
            CompletableFuture<OnionProbeSession.Result> result = start(call);
            assertTrue(greeted.await(2, TimeUnit.SECONDS)); call.cancel();
            assertEquals(OnionProbeSession.State.CANCELLED, result.get(2, TimeUnit.SECONDS).state);
        });
    }
    @Test public void cancellationDuringBodyClosesChannel() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        harness(socket -> {
            negotiate(socket, 0); readRequest(socket);
            reply(socket, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx"); headers.countDown();
            assertEquals(-1, socket.getInputStream().read());
        }, port -> {
            FixedOnionProbe call = HostTorSockets.probe(port, () -> true);
            CompletableFuture<OnionProbeSession.Result> result = start(call);
            assertTrue(headers.await(2, TimeUnit.SECONDS)); call.cancel();
            assertEquals(OnionProbeSession.State.CANCELLED, result.get(2, TimeUnit.SECONDS).state);
        });
    }
    @Test public void readinessLossClosesChannelWithoutUiWatchdog() throws Exception {
        AtomicBoolean ready = new AtomicBoolean(true);
        harness(socket -> {
            greeting(socket); ready.set(false);
            assertEquals(-1, socket.getInputStream().read());
        }, port -> assertEquals(OnionProbeSession.State.CANCELLED,
                start(HostTorSockets.probe(port, ready::get)).get(2, TimeUnit.SECONDS).state));
    }
    @Test public void cancelledOrUnreadyBeforeStartNeverConnects() throws Exception {
        try (ServerSocket listener = listener()) {
            listener.setSoTimeout(200);
            FixedOnionProbe cancelled = HostTorSockets.probe(listener.getLocalPort(), () -> true);
            cancelled.cancel();
            assertEquals(OnionProbeSession.State.CANCELLED, start(cancelled).get(2, TimeUnit.SECONDS).state);
            assertEquals(OnionProbeSession.State.CANCELLED,
                    start(HostTorSockets.probe(listener.getLocalPort(), () -> false)).get(2, TimeUnit.SECONDS).state);
            assertThrows(SocketTimeoutException.class, listener::accept);
        }
    }
    @Test public void invalidPortAndRepeatedExecutionAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> HostTorSockets.probe(0, () -> true));
        assertThrows(IllegalArgumentException.class, () -> HostTorSockets.probe(65536, () -> true));
        assertThrows(IllegalArgumentException.class, () -> HostTorSockets.probe(9050, () -> true, 0));
        assertThrows(IllegalArgumentException.class, () -> HostTorSockets.probe(9050, () -> true, 30001));
        FixedOnionProbe call = HostTorSockets.probe(1, () -> false);
        start(call).get(2, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> start(call));
    }
    private static void responseFailure(String response) throws Exception {
        harness(socket -> { negotiate(socket, 0); readRequest(socket); reply(socket, response); },
                port -> {
                    OnionProbeSession.Result result = run(port, 3000); assertFailed(result);
                    assertEquals(OnionProbeSession.Stage.HTTP_READ, result.stage);
                });
    }
    private static CompletableFuture<OnionProbeSession.Result> start(FixedOnionProbe call) {
        CompletableFuture<OnionProbeSession.Result> future = new CompletableFuture<>(); call.start(future::complete); return future;
    }
    private static OnionProbeSession.Result run(int port, int deadline) throws Exception {
        return start(HostTorSockets.probe(port, () -> true, deadline)).get(5, TimeUnit.SECONDS);
    }
    private static void assertFailed(OnionProbeSession.Result result) { assertEquals(OnionProbeSession.State.FAILED, result.state); }
    private static String repeat(char value, int size) { char[] chars = new char[size]; java.util.Arrays.fill(chars, value); return new String(chars); }
    private static ServerSocket listener() throws IOException { return new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1})); }
    private static void greeting(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte());
    }
    private static void negotiate(Socket socket, int status) throws IOException {
        greeting(socket); negotiateAfterGreeting(socket, status);
    }
    private static void negotiateAfterGreeting(Socket socket, int status) throws IOException {
        bytes(socket, new byte[]{5, 0});
        readConnectCommand(socket);
        bytes(socket, new byte[]{5, (byte) status, 0, 1, 127, 0, 0, 1, 0, 1});
    }
    private static void readConnectCommand(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte());
        assertEquals(0, input.readUnsignedByte()); assertEquals(3, input.readUnsignedByte());
        byte[] host = new byte[input.readUnsignedByte()]; input.readFully(host);
        assertEquals(FixedOnionProbe.HOST, new String(host, StandardCharsets.US_ASCII)); assertEquals(80, input.readUnsignedShort());
    }
    private static String readRequest(Socket socket) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream(); DataInputStream input = new DataInputStream(socket.getInputStream());
        while (!text.toString("US-ASCII").endsWith("\r\n\r\n")) {
            assertTrue(text.size() < 8192); text.write(input.readUnsignedByte());
        }
        return text.toString("US-ASCII");
    }
    private static void reply(Socket socket, String text) throws IOException { bytes(socket, text.getBytes(StandardCharsets.US_ASCII)); }
    private static void bytes(Socket socket, byte[] bytes) throws IOException { socket.getOutputStream().write(bytes); socket.getOutputStream().flush(); }
    private interface Server { void run(Socket socket) throws Exception; }
    private interface Client { void run(int port) throws Exception; }
    private static void harness(Server server, Client client) throws Exception {
        try (ServerSocket listener = listener()) {
            listener.setSoTimeout(5000); ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> peer = executor.submit(() -> {
                try (Socket socket = listener.accept()) { socket.setSoTimeout(5000); server.run(socket); }
                catch (Exception problem) { throw new RuntimeException(problem); }
            });
            try { client.run(listener.getLocalPort()); peer.get(6, TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); }
        }
    }
}
