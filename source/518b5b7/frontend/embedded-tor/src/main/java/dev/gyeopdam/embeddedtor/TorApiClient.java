package dev.gyeopdam.embeddedtor;

import android.os.Handler;
import android.os.Looper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Main-thread app facade. JSON and credentials never leave native code except through Tor. */
final class TorApiClient {
    interface Callback { void complete(int status, JSONObject body, String errorCode); }
    private final EmbeddedTorController controller;
    private final TorSocketIo.Factory sockets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task,"tor-api"); thread.setDaemon(true); return thread;
    });
    private final Set<Call> calls = new LinkedHashSet<>();
    private final Runnable cancellation = this::cancelAll;
    private String origin = "";
    private volatile long generation;
    private volatile boolean closed;
    TorApiClient(EmbeddedTorController controller) { this(controller, AndroidTorSocket::new); }
    TorApiClient(EmbeddedTorController controller, TorSocketIo.Factory sockets) {
        this.controller = controller; this.sockets = sockets; controller.addCancellation(cancellation);
    }
    boolean configureOnion(String value) {
        requireMain();
        final String canonical;
        try { canonical = value == null || value.isEmpty() ? "" : OnionApiRequest.canonicalOrigin(value); }
        catch (IllegalArgumentException invalid) { return false; }
        if (!canonical.equals(origin)) { origin = canonical; cancelAll(); }
        return true;
    }
    String configuredOnion() { return origin; }
    void request(String method, String path, JSONObject body, String sessionToken, String deviceToken, Callback callback) {
        request(method,path,body,sessionToken,deviceToken,"",callback);
    }
    void request(String method, String path, JSONObject body, String sessionToken, String deviceToken,
                 String boundOrigin, Callback callback) {
        requireMain();
        if (closed) { callback.complete(0,new JSONObject(),"CLIENT_CLOSED"); return; }
        if (origin.isEmpty()) { callback.complete(0,new JSONObject(),"SERVER_NOT_CONFIGURED"); return; }
        if (calls.size() >= 4) { callback.complete(0,new JSONObject(),"BUSY"); return; }
        TorReadinessGate.ReadyLease lease = controller.lease();
        if (lease == null) { callback.complete(0,new JSONObject(),"TOR_NOT_READY"); return; }
        final byte[] bytes;
        try {
            String serialized = body == null ? "" : body.toString();
            if (serialized == null || serialized.length() > OnionApiRequest.MAX_REQUEST_BYTES
                    || body != null && !JsonBounds.safe(serialized)) throw new IllegalArgumentException("INVALID_BODY");
            bytes = serialized.getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException invalid) { callback.complete(0,new JSONObject(),"INVALID_REQUEST"); return; }
        final OnionApiRequest request;
        try { request = new OnionApiRequest(origin,method,path,bytes,sessionToken,deviceToken,boundOrigin); }
        catch (IllegalArgumentException invalid) { Arrays.fill(bytes,(byte)0); callback.complete(0,new JSONObject(),"INVALID_REQUEST"); return; }
        Arrays.fill(bytes,(byte)0);
        final long epoch = generation;
        Call call = new Call(new OnionApiTransport(lease.endpoint,
                () -> !closed && epoch == generation && controller.current(lease),OnionApiTransport.DEADLINE_MS,sockets),callback);
        calls.add(call);
        workers.execute(() -> {
            OnionApiTransport.Result response = call.transport.execute(request);
            JSONObject parsed = new JSONObject(); String error = response.error == OnionApiTransport.Error.NONE ? "" : response.error.name();
            if (error.isEmpty() && response.status >= 300 && response.status < 400) error = "REDIRECT_REFUSED";
            if (error.isEmpty() && response.body.length > 0) {
                try {
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body)).toString();
                    if (!JsonBounds.safe(text)) throw new IllegalArgumentException("JSON_BOUNDS");
                    JSONTokener tokener = new JSONTokener(text);
                    Object value = tokener.nextValue();
                    if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("JSON_OBJECT_REQUIRED");
                    parsed = (JSONObject)value;
                } catch (Exception invalid) { error = "INVALID_JSON"; }
            }
            Arrays.fill(response.body,(byte)0);
            final JSONObject result = parsed; final String errorCode = error;
            main.post(() -> {
                if (!calls.remove(call)) return;
                if (closed || epoch != generation || !controller.current(lease)) call.callback.complete(0,new JSONObject(),"CANCELLED");
                else call.callback.complete(response.status,result,errorCode);
            });
        });
    }
    void cancelAll() {
        requireMain(); generation++;
        ArrayList<Call> pending = new ArrayList<>(calls); calls.clear();
        // Complete safety teardown before invoking application callbacks. One faulty
        // callback must not strand other sockets or abort controller opt-out/close.
        for (Call call : pending) call.transport.cancel();
        for (Call call : pending) {
            try { call.callback.complete(0,new JSONObject(),"CANCELLED"); }
            catch (RuntimeException ignored) { /* No callback contents or exception text are logged. */ }
        }
    }
    void close() {
        requireMain(); if (closed) return; closed = true; cancelAll(); controller.removeCancellation(cancellation); workers.shutdownNow();
    }
    private static final class Call {
        final OnionApiTransport transport; final Callback callback;
        Call(OnionApiTransport transport, Callback callback) { this.transport=transport;this.callback=callback; }
    }
    private static void requireMain() { if (Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("MAIN_THREAD_REQUIRED"); }
}
