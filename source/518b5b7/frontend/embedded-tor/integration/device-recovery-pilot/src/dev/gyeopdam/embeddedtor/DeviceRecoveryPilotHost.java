package dev.gyeopdam.embeddedtor;

import android.content.Context;
import android.system.Os;
import dev.gyeopdam.embeddedtor.host.FakeAndroidKeyStoreProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * HOST INTEGRATION ONLY. Runs the unchanged production coordinator, response rules and encrypted
 * checkpoint store against a real ephemeral Go pilot. Direct HTTP is confined to this test adapter.
 * Android Context, AtomicFile, Os and AndroidKeyStore use the existing explicit host doubles:
 * file writes/renames are real, directory fsync and hardware isolation are NOT established.
 * Coordinator/store recreation is real; the host JVM and its synthetic Keystore remain alive.
 */
public final class DeviceRecoveryPilotHost {
    private static final String HOST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaam2dqd.onion";
    private static final String ORIGIN = "http://" + HOST;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static int assertions;

    public static void main(String[] args) throws Exception {
        check(args.length == 2 && Security.getProvider("AndroidKeyStore") == null, "isolated host required");
        Security.addProvider(new FakeAndroidKeyStoreProvider());
        try (Pilot pilot = new Pilot(args[0], Path.of(args[1]))) {
            pilot.start();
            lostResponseAndRestart(pilot);
            fallbackRequiresCleanUnauthorized(pilot);
            check(Os.openDescriptorCount() == 0, "storage descriptors leaked");
            System.out.println("PASS production coordinator/Go recovery interop: " + assertions + " assertions");
        } finally {
            FakeAndroidKeyStoreProvider.reset();
            Security.removeProvider("AndroidKeyStore");
        }
    }

    private static void lostResponseAndRestart(Pilot pilot) throws Exception {
        Fixture f = new Fixture(pilot, "lost-response");
        String caller = f.login(f.oldCapability);
        Client first = new Client(f, caller);
        first.api.loseRecoveryResponse = true;
        first.coordinator.start(f.device, f.oldCapability, first.results::add);
        check(first.results.equals(List.of("RECOVERY_RESULT_UNCONFIRMED")), "lost response falsely confirmed");
        check(first.api.calls.size() == 1 && first.api.calls.get(0).actualStatus == 200, "real commit did not succeed");
        String originalRequest = first.api.calls.get(0).body;
        PendingDeviceRecoveryStore.Pending pending = f.store().load();
        check(pending != null && !pending.confirmed, "unconfirmed checkpoint missing");
        check(!pending.replacementAccountToken.equals(f.oldCapability), "capability did not rotate");
        check(!first.coordinator.requiresAcknowledgement(), "lost response allowed acknowledgement");
        first.coordinator.acknowledge(pending.recoveryId, first.results::add);
        check(first.results.get(1).equals("RECOVERY_ACK_NOT_ALLOWED"), "unconfirmed key was discarded");
        byte[] encrypted = Files.readAllBytes(f.context.directory.resolve("tortalk-device-recovery-v1.enc"));
        String disk = new String(encrypted, StandardCharsets.ISO_8859_1);
        check(!disk.contains(f.oldCapability) && !disk.contains(pending.replacementAccountToken), "plaintext checkpoint key");
        first.coordinator.close();
        pilot.restart();

        f.assertOldCredentialsRefused(originalRequest);
        pilot.expect("GET", "/v1/me", null, caller, 200);
        String newerSession = f.login(pending.replacementAccountToken);
        String newerDevice = "demo-device-new-" + suffix();
        f.enroll(newerDevice, newerSession);

        Client resumed = new Client(f, null);
        resumed.coordinator.resume(resumed.results::add);
        check(resumed.results.equals(List.of("")), "replacement authentication/retry failed");
        check(resumed.api.calls.size() == 2, "retry made unexpected requests");
        Call login = resumed.api.calls.get(0), retry = resumed.api.calls.get(1);
        check(login.path.equals("/v1/auth/sessions") && login.actualStatus == 201, "real auth creation status drift");
        check(json(login.body).getString("account_token").equals(pending.replacementAccountToken), "did not try replacement first");
        check(retry.actualStatus == 200 && retry.body.equals(originalRequest), "recovery retry was not byte-exact");
        check(resumed.authenticated.size() == 1, "replacement session not installed");
        check(resumed.coordinator.requiresAcknowledgement(), "successful recovery lacks acknowledgement gate");
        check(f.store().load().confirmed, "server confirmation not durably recorded");
        check(f.store().load().replacementAccountToken.equals(pending.replacementAccountToken), "replacement key changed on retry");
        for (String session : List.of(caller, newerSession, resumed.authenticated.get(0).sessionToken))
            pilot.expect("GET", "/v1/me", null, session, 200);
        JSONArray devices = pilot.expect("GET", "/v1/devices", null, newerSession, 200).getJSONArray("devices");
        check(revoked(devices, f.device) && !revoked(devices, newerDevice), "retry revoked a newer device");
        f.assertOldCredentialsRefused(originalRequest);
        resumed.coordinator.close();

        // The user acknowledgement event is modeled explicitly, only after persisted server success.
        Client afterRecreation = new Client(f, newerSession);
        check(afterRecreation.coordinator.requiresAcknowledgement(), "confirmed key lost on recreation");
        afterRecreation.coordinator.acknowledge(pending.recoveryId, afterRecreation.results::add);
        check(afterRecreation.results.equals(List.of("")) && f.store().load() == null, "acknowledgement was not durable");
        byte[] tombstone = new KeystoreBlobStore(f.context, "device-recovery").read();
        check(PendingDeviceRecoverySchema.decode(tombstone).fields == null, "acknowledgement retained checkpoint secrets");
        afterRecreation.coordinator.close();
        System.out.println("PASS real commit/lost callback, Go restart, auth 201, exact retry, old credential refusal, newer session/device preservation, durable key acknowledgement");
    }

    private static void fallbackRequiresCleanUnauthorized(Pilot pilot) throws Exception {
        Fixture f = new Fixture(pilot, "fallback");
        PendingDeviceRecoveryStore.Pending pending = f.store().prepare(ORIGIN, f.account, f.device, f.oldCapability);
        // Each fault is injected after the replacement login really returns 401. None may use old proof.
        for (String fault : List.of("timeout", "unavailable", "ambiguous", "transport-error")) {
            Client client = new Client(f, null);
            client.api.authFault = fault;
            client.coordinator.resume(client.results::add);
            check(client.api.calls.size() == 1 && client.api.calls.get(0).actualStatus == 401, "fault fell back to old proof");
            check(client.authenticated.isEmpty() && client.results.equals(List.of("RECOVERY_RESULT_UNCONFIRMED")), "ambiguous auth accepted");
            check(!f.store().load().confirmed && f.store().load().recoveryId.equals(pending.recoveryId), "fault changed checkpoint");
            client.coordinator.close();
        }
        Client clean = new Client(f, null);
        clean.coordinator.resume(clean.results::add);
        check(clean.results.equals(List.of("")) && clean.api.calls.size() == 3, "clean unauthorized did not recover");
        Call replacement = clean.api.calls.get(0), old = clean.api.calls.get(1), recovery = clean.api.calls.get(2);
        check(replacement.actualStatus == 401 && json(replacement.body).getString("account_token").equals(pending.replacementAccountToken), "replacement login contract");
        check(old.actualStatus == 201 && json(old.body).getString("account_token").equals(f.oldCapability), "old fallback auth 201 contract");
        check(recovery.actualStatus == 200 && f.store().load().confirmed, "fallback recovery not confirmed");
        f.assertOldCredentialsRefused(recovery.body);
        clean.coordinator.close();
        System.out.println("PASS replacement-first real 401/old auth 201 fallback; timeout, 503, ambiguous 401 and transport errors never fall back");
    }

    private static final class Client {
        final DeviceRecoveryCoordinator coordinator;
        final LoopbackApi api;
        final List<String> results = new ArrayList<>();
        final List<AppSessionStore.Session> authenticated = new ArrayList<>();
        Client(Fixture f, String token) {
            api = new LoopbackApi(f);
            coordinator = new DeviceRecoveryCoordinator(new StoreAdapter(f.store()), api, Runnable::run, Runnable::run,
                new DeviceRecoveryCoordinator.Listener() {
                    public void changed() { }
                    public void sessionAuthenticated(AppSessionStore.Session session) { authenticated.add(session); }
                });
            coordinator.updateContext(ORIGIN, token == null ? null : new AppSessionStore.Session(ORIGIN, f.account, "", "", "", token));
            coordinator.load(results::add);
            check(results.equals(List.of("")), "checkpoint load failed");
            results.clear();
        }
    }

    /** Only adapts the interface; every store operation is production code. */
    private static final class StoreAdapter implements DeviceRecoveryCoordinator.Store {
        final PendingDeviceRecoveryStore store;
        StoreAdapter(PendingDeviceRecoveryStore store) { this.store = store; }
        public PendingDeviceRecoveryStore.Pending load() throws IOException { return store.load(); }
        public PendingDeviceRecoveryStore.Pending prepare(String o, String a, String d, String old) throws IOException { return store.prepare(o, a, d, old); }
        public PendingDeviceRecoveryStore.Pending markConfirmed(String id) throws IOException { return store.markConfirmed(id); }
        public void acknowledge(String id) throws IOException { store.acknowledge(id); }
        public void resetAfterConfirmation(String id) throws IOException { store.resetAfterConfirmation(id); }
        public long epoch() { return PendingDeviceRecoveryStore.mutationEpoch(); }
        public boolean dispatchIfCurrent(long epoch, Runnable request) { return PendingDeviceRecoveryStore.dispatchIfCurrent(epoch, request); }
    }

    private record Call(String path, String body, int actualStatus) { }
    private record Response(int status, JSONObject body) { }
    /** TEST-ONLY direct loopback transport and explicitly injected callback faults. */
    private static final class LoopbackApi implements DeviceRecoveryCoordinator.Api {
        final Fixture fixture;
        final List<Call> calls = new ArrayList<>();
        boolean loseRecoveryResponse;
        String authFault = "";
        LoopbackApi(Fixture fixture) { this.fixture = fixture; }
        public void request(String method, String path, JSONObject body, AppSessionStore.Session owner, String origin, DeviceRecoveryCoordinator.Reply callback) {
            try {
                check(origin.equals(ORIGIN) && method.equals("POST"), "unexpected coordinator destination");
                boolean recovery = path.equals("/v1/devices/" + fixture.device + "/recover");
                check(recovery || path.equals("/v1/auth/sessions"), "unexpected coordinator route");
                PendingDeviceRecoveryStore.Pending saved = fixture.store().load();
                check(saved != null, "HTTP preceded durable checkpoint");
                if (recovery) {
                    check(body.getString("recovery_id").equals(saved.recoveryId)
                        && body.getString("account_token").equals(saved.oldAccountToken)
                        && body.getString("replacement_account_token").equals(saved.replacementAccountToken), "HTTP differs from durable checkpoint");
                }
                Response actual = fixture.pilot.request(method, path, body, owner == null ? "" : owner.sessionToken);
                calls.add(new Call(path, body.toString(), actual.status));
                if (recovery && loseRecoveryResponse) {
                    check(actual.status == 200, "cannot lose an uncommitted response");
                    loseRecoveryResponse = false;
                    callback.complete(0, new JSONObject(), "TIMEOUT");
                } else if (!recovery && !authFault.isEmpty()) {
                    check(actual.status == 401 && actual.body.length() == 1 && "UNAUTHORIZED".equals(actual.body.opt("code")), "fault " + authFault + " lacks real unauthorized response: " + actual.status);
                    switch (authFault) {
                        case "timeout" -> callback.complete(0, new JSONObject(), "TIMEOUT");
                        case "unavailable" -> callback.complete(503, actual.body, "");
                        case "ambiguous" -> callback.complete(401, actual.body.put("extra", true), "");
                        case "transport-error" -> callback.complete(401, actual.body, "TIMEOUT");
                        default -> throw new AssertionError("unknown fault");
                    }
                } else callback.complete(actual.status, actual.body, "");
            } catch (Exception error) { throw new AssertionError("loopback adapter failed", error); }
        }
    }

    private static final class Fixture {
        final Pilot pilot;
        final TestContext context;
        final String account = "demo-account-" + suffix(), device = "demo-device-" + suffix();
        final String oldCapability = capability("gd_acct_"), oldSession;
        Fixture(Pilot pilot, String name) throws Exception {
            this.pilot = pilot;
            context = new TestContext(Files.createDirectory(pilot.root.resolve(name)));
            pilot.expect("POST", "/v1/accounts", object("account_id", account, "account_token", oldCapability, "registration_token", pilot.join), "", 201);
            oldSession = login(oldCapability);
            enroll(device, oldSession);
        }
        PendingDeviceRecoveryStore store() { return new PendingDeviceRecoveryStore(context); }
        String login(String capability) throws Exception {
            return pilot.expect("POST", "/v1/auth/sessions", object("account_id", account, "account_token", capability), "", 201).getString("session_token");
        }
        void enroll(String id, String session) throws Exception {
            byte[] key = new byte[32]; RANDOM.nextBytes(key);
            pilot.expect("POST", "/v1/devices", object("device_id", id, "device_token", capability("gd_dev_"), "public_key_base64", Base64.getEncoder().encodeToString(key)), session, 201);
        }
        void assertOldCredentialsRefused(String recoveryRequest) throws Exception {
            pilot.expect("GET", "/v1/me", null, oldSession, 401);
            pilot.expect("POST", "/v1/auth/sessions", object("account_id", account, "account_token", oldCapability), "", 401);
            pilot.expect("POST", "/v1/devices/" + device + "/recover", json(recoveryRequest), oldSession, 401);
        }
    }

    private static final class TestContext extends Context {
        final Path directory;
        TestContext(Path directory) { this.directory = directory; }
        public Context getApplicationContext() { return this; }
        public String getPackageName() { return "dev.gyeopdam.embeddedtor.recoverypilot"; }
        public File getNoBackupFilesDir() { return directory.toFile(); }
    }

    private static final class Pilot implements AutoCloseable {
        final String binary, join = capability("tt_join_");
        final Path root, config;
        final int port;
        Process process;
        Pilot(String binary, Path root) throws Exception {
            this.binary = binary; this.root = root;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
            config = root.resolve("pilot.json");
            Files.writeString(config, object("listen", "127.0.0.1:" + port, "authority", HOST,
                "state_dir", root.resolve("server").toString(), "registration_token_hash",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(join.getBytes(StandardCharsets.UTF_8)))).toString());
        }
        void start() throws Exception {
            process = new ProcessBuilder(binary, "--config", config.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            for (int attempt = 0; attempt < 150; attempt++) {
                check(process.isAlive(), "pilot exited during startup");
                try { expect("GET", "/readyz", null, "", 200); return; }
                catch (IOException notReady) { Thread.sleep(20); }
            }
            throw new AssertionError("pilot startup timeout");
        }
        void restart() throws Exception { close(); start(); }
        public void close() throws Exception {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); check(process.waitFor(10, TimeUnit.SECONDS), "pilot shutdown timeout"); }
            }
        }
        JSONObject expect(String method, String path, JSONObject body, String session, int status) throws Exception {
            Response response = request(method, path, body, session);
            check(response.status == status, method + " " + path + ": expected " + status + ", got " + response.status);
            return response.body;
        }
        Response request(String method, String path, JSONObject body, String session) throws Exception {
            // URL is always a literal loopback address; onion is only the server's reviewed Host gate.
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(3000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method); connection.setRequestProperty("Host", HOST); connection.setRequestProperty("Content-Type", "application/json");
            if (!session.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + session);
            try {
                if (body != null) {
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    // Buffered request mode preserves 401 response bodies in HttpURLConnection.
                    connection.setDoOutput(true);
                    try (var output = connection.getOutputStream()) { output.write(bytes); }
                }
                int status = connection.getResponseCode();
                try (InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                    byte[] bytes = input == null ? new byte[0] : input.readNBytes(65537);
                    check(bytes.length <= 65536, "unbounded pilot response");
                    return new Response(status, bytes.length == 0 ? new JSONObject() : json(new String(bytes, StandardCharsets.UTF_8)));
                }
            } finally { connection.disconnect(); }
        }
    }

    private static boolean revoked(JSONArray devices, String id) throws Exception {
        for (int i = 0; i < devices.length(); i++) if (devices.getJSONObject(i).getString("device_id").equals(id)) return devices.getJSONObject(i).getBoolean("revoked");
        throw new AssertionError("expected device missing");
    }
    private static String capability(String prefix) { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String suffix() { byte[] bytes = new byte[16]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    private static JSONObject json(String text) throws Exception { return new JSONObject(text); }
    private static JSONObject object(String... fields) throws Exception { JSONObject value = new JSONObject(); for (int i = 0; i < fields.length; i += 2) value.put(fields[i], fields[i + 1]); return value; }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
