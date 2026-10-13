package dev.gyeopdam.embeddedtor;
import android.content.Context;
import android.os.Handler;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
public final class CoordinatorReview  {
    static final String ORIGIN="http://"+"a".repeat(56)+".onion";
    static final String ROOM="demo-room-main", ALICE="demo-account-alice", BOB="demo-account-bob";
    static String token(String prefix) {
        return prefix+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    }
    static void check(boolean yes,String message) {
        if(!yes)throw new AssertionError(message);
    }
    static JSONObject json(Object...pairs)throws Exception  {
        JSONObject j=new JSONObject();
        for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);
        return j;
    }
    static final class Completion implements MessageCoordinator.Callback  {
        String result;
        public void complete(String error) {
            result=error;
        }
        void await(String expected)throws Exception {
            while(result==null)Handler.pump();
            check(result.equals(expected),"expected "+expected+", received "+result);
        }
    }
    static final class LocalRoomsCompletion implements MessageCoordinator.LocalRoomsCallback {
        JSONObject page;
        String result;
        public void complete(JSONObject value,String error) { page=value;result=error; }
        void await(String expected)throws Exception {
            while(result==null)Handler.pump();
            check(result.equals(expected),"expected archive listing "+expected+", received "+result);
        }
    }
    static final class Memory implements SignalClient.StateStorage  {
        byte[] data;
        public byte[] load() {
            return data==null?null:data.clone();
        }
        public void save(byte[] b) {
            data=b.clone();
        }
    }
    static SignalClient peer(String account,String device)throws Exception {
        SignalClient s=new SignalClient(new Memory());
        s.initialize(ORIGIN,account,device,20);
        return s;
    }
    static final class Fixture  {
        final Context context=new Context();
        final TorApiClient api=new TorApiClient(ORIGIN);
        final MessageCoordinator coordinator;
        AppSessionStore.Session session;
        Fixture(String account) {
            session=new AppSessionStore.Session(ORIGIN,account,token("gd_acct_"),"","",token("gd_sess_"));
            coordinator=new MessageCoordinator(context,api,new MessageCoordinator.Listener() {
                public void changed() {
                }
                public void sessionChanged(AppSessionStore.Session v) {
                    session=v;
                }
            }
            );
            coordinator.bindSession(session);
        }
        SignalClient local() {
            String ns=SignalProfile.namespace(session.serverOnion,session.accountId);
            KeystoreBlobStore blob=new KeystoreBlobStore(context,ns);
            return new SignalClient(new SignalClient.StateStorage() {
                public byte[] load()throws java.io.IOException {
                    return blob.read();
                }
                public void save(byte[] b)throws java.io.IOException {
                    blob.write(b);
                }
            }
            );
        }
        void enroll()throws Exception {
            Completion done=new Completion();
            coordinator.enroll(done);
            ownedHistory(api, new JSONArray());
            TorApiClient.Request r=api.pop();
            register(r);
            TorApiClient.Request p=api.pop();
            check(p.path.endsWith("/prekeys"),"publication request");
            p.callback.complete(200,json("device_id",session.deviceId,"published_pairs",20,"remaining_pairs",20),"");
            done.await("");
        }
        void register(TorApiClient.Request r)throws Exception {
            check(r.path.equals("/v1/devices"),"register path");
            r.callback.complete(200,json("device",json("account_id",session.accountId,"device_id",r.body.getString("device_id"),"public_key_base64",r.body.getString("public_key_base64")),"device_token",r.body.getString("device_token")),"");
        }
        void open(String room,SignalClient other,boolean trusted)throws Exception {
            Completion done=new Completion();
            coordinator.openRoom(room,other.accountId(),done);
            check(done.result==null,"open early result: "+done.result);
            TorApiClient.Request r=api.pop();
            r.callback.complete(200,directory(other),"");
            done.await(trusted?"":"PEER_NOT_VERIFIED");
        }
        void verify()throws Exception {
            Completion done=new Completion();
            coordinator.verifyPeer(coordinator.pendingPeer(),done);
            done.await("");
        }
    }
    static JSONObject directory(SignalClient s)throws Exception {
        return json("devices",new JSONArray().put(json("account_id",s.accountId(),"device_id",s.deviceId(),"identity_key_base64",s.identityKeyBase64(),"protocol_device_id",s.protocolDeviceId(),"public_key_base64",s.devicePublicKeyBase64(),"key_digest",SignalJson.keyDigest(s.identityKeyBase64()))));
    }
    static JSONObject bundle(TorApiClient.Request claim,SignalClient peer)throws Exception {
        SignalClient.Publication p=peer.publication();
        JSONObject b=SignalJson.publication(p);
        b.remove("one_time_prekeys");
        b.remove("kyber_prekeys");
        b.put("one_time_prekey",SignalJson.publicKey(p.oneTimePrekeys.get(0))).put("kyber_prekey",SignalJson.publicKey(p.kyberPrekeys.get(0))).put("claim_id",claim.body.getString("claim_id")).put("room_id",claim.path.split("/")[3]).put("account_id",peer.accountId()).put("device_id",peer.deviceId()).put("key_digest",SignalJson.keyDigest(peer.identityKeyBase64()));
        return b;
    }
    static void registrationRetry()throws Exception {
        Fixture f=new Fixture("demo-account-retry");
        Completion first=new Completion();
        f.coordinator.enroll(first);
        ownedHistory(f.api, new JSONArray());
        TorApiClient.Request original=f.api.pop();
        check(f.local().deviceToken().equals(original.body.getString("device_token")),"credential durable before request");
        original.callback.complete(0,new JSONObject(),"TIMEOUT");
        first.await("TIMEOUT");
        Completion again=new Completion();
        f.coordinator.enroll(again);
        ownedHistory(f.api, new JSONArray());
        TorApiClient.Request retry=f.api.pop();
        check(retry.body.toString().equals(original.body.toString()),"registration changed across retry");
        f.register(retry);
        TorApiClient.Request publication=f.api.pop();
        publication.callback.complete(200,json("device_id",f.session.deviceId,"published_pairs",20,"remaining_pairs",20),"");
        again.await("");
        f.coordinator.close();
        System.out.println("PASS registration timeout retries durable exact credential");
    }
    static void pinAndOutbox()throws Exception {
        Fixture f=new Fixture("demo-account-send");
        f.enroll();
        SignalClient bob=peer(BOB,"demo-device-bob");
        f.open(ROOM,bob,false);
        Completion denied=new Completion();
        f.coordinator.sendText(ROOM,"test",denied);
        denied.await("PEER_NOT_VERIFIED");
        check(f.api.requests.isEmpty(),"unverified network send");
        f.verify();
        Completion sent=new Completion();
        f.coordinator.sendText(ROOM,"synthetic test",sent);
        TorApiClient.Request claim=f.api.pop();
        claim.callback.complete(200,bundle(claim,bob),"");
        TorApiClient.Request payload=f.api.pop();
        check(f.local().pendingOutbox().size()==1,"outbox not durable before HTTP");
        payload.callback.complete(0,new JSONObject(),"TIMEOUT");
        sent.await("QUEUED_TIMEOUT");
        Completion retried=new Completion();
        f.coordinator.retryPending(ROOM,retried);
        TorApiClient.Request retry=f.api.pop();
        check(payload.body.toString().equals(retry.body.toString()),"outbox wire changed");
        JSONObject e=retry.body.getJSONObject("envelope");
        retry.callback.complete(200,json("message_id",retry.body.getString("message_id"),"room_id",ROOM,"sender_id",e.getString("sender_id"),"recipient_id",e.getString("recipient_id"),"status","accepted"),"");
        retried.await("");
        check(f.local().pendingOutbox().isEmpty(),"not accepted locally");
        f.coordinator.close();
        System.out.println("PASS explicit pin required; exact durable ciphertext on retry");
    }
    static void receiveCommit()throws Exception {
        Fixture f=new Fixture("demo-account-receive");
        f.enroll();
        SignalClient bob=peer("demo-account-sender","demo-device-sender");
        f.open(ROOM,bob,false);
        f.verify();
        SignalClient local=f.local();
        bob.acceptPeer(ROOM,f.session.accountId,local.deviceId(),local.protocolDeviceId(),local.identityKeyBase64());
        bob.acceptBundle(ROOM,f.session.accountId,local.deviceId(),local.publication().bundle(0));
        SignalClient.Envelope sent=bob.stageText(ROOM,f.session.accountId,local.deviceId(),"demo-message-receive","synthetic late message");
        JSONObject e=SignalJson.sendRequest(bob,sent).getJSONObject("envelope");
        JSONObject receipt=json("message_id",sent.messageId,"room_id",ROOM,"sender_id",bob.accountId(),"recipient_id",f.session.accountId,"send_until","2000-01-01T00:00:00Z");
        JSONObject response=json("receipt",receipt,"envelope",e);
        Completion fail=new Completion();
        f.coordinator.poll(ROOM,fail);
        TorApiClient.Request inbox=f.api.pop();
        inbox.callback.complete(200,json("receipts",new JSONArray().put(receipt)),"");
        TorApiClient.Request get=f.api.pop();
        KeystoreBlobStore.failNamespace=SignalProfile.namespace(ORIGIN,f.session.accountId);
        KeystoreBlobStore.failures=1;
        get.callback.complete(200,response,"");
        fail.await("CRYPTO_OR_STORAGE_FAILED");
        check(f.api.requests.isEmpty(),"ACK after failed commit");
        check(local.inbox().isEmpty(),"failed write exposed inbox");
        Completion success=new Completion();
        f.coordinator.poll(ROOM,success);
        f.api.pop().callback.complete(200,json("receipts",new JSONArray().put(receipt)),"");
        f.api.pop().callback.complete(200,response,"");
        TorApiClient.Request ack=f.api.pop();
        check(ack.path.endsWith("/ack"),"expected ACK");
        check(local.inbox().size()==1&&local.inbox().get(0).text.equals("synthetic late message"),"ACK before durable plaintext");
        ack.callback.complete(200,json("message_id",sent.messageId,"recipient_id",f.session.accountId,"status","recipient-acknowledged"),"");
        success.await("");
        check(f.coordinator.messages().length()==1,"missing local message");
        f.coordinator.close();
        System.out.println("PASS no ACK on write failure; ratchet+plaintext before ACK; expired send metadata does not block receive");
    }
    static void acceptedCommitFailure() throws Exception {
        Fixture f = new Fixture("demo-account-accepted-error");
        f.enroll();
        SignalClient bob = peer("demo-account-error-bob", "demo-device-error-bob");
        f.open(ROOM, bob, false);
        f.verify();
        String messageId = "demo-message-accepted-write-failure";
        String text = "same synthetic draft";
        int[] staged = {0};
        Runnable checkpoint = () -> {
            staged[0]++;
            try {
                check(f.local().sentMessages().size() == 1, "checkpoint preceded durable commit");
                check(f.api.requests.isEmpty(), "HTTP preceded staging checkpoint");
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        };
        Completion first = new Completion();
        f.coordinator.sendText(ROOM, messageId, text, checkpoint, first);
        TorApiClient.Request claim = f.api.pop();
        claim.callback.complete(200, bundle(claim, bob), "");
        TorApiClient.Request accepted = f.api.pop();
        check(staged[0] == 1, "missing staging callback");
        check(f.coordinator.messages().length() == 1, "pending message hidden before HTTP");
        check(f.coordinator.messages().getJSONObject(0).getString("status").equals("pending"),
                "premature relay acceptance");
        KeystoreBlobStore.failNamespace = SignalProfile.namespace(ORIGIN, f.session.accountId);
        KeystoreBlobStore.failures = 1;
        accept(accepted, ROOM);
        first.await("QUEUED_CRYPTO_OR_STORAGE_FAILED");
        check(f.local().pendingOutbox().size() == 1, "first envelope lost");
        check(f.coordinator.messages().length() == 1, "pending message hidden after write failure");

        Completion second = new Completion();
        f.coordinator.sendText(ROOM, messageId, text, checkpoint, second);
        TorApiClient.Request retry = f.api.pop();
        check(staged[0] == 2, "retry did not confirm its durable staging checkpoint");
        check(accepted.body.toString().equals(retry.body.toString()), "retry changed exact wire payload");
        check(f.local().sentMessages().size() == 1, "same draft created another outbox record");
        accept(retry, ROOM);
        second.await("");
        check(f.local().pendingOutbox().isEmpty(), "successful acceptance not committed");
        check(f.local().sentMessages().size() == 1, "retry duplicated sent history");
        f.coordinator.close();
        System.out.println("PASS acceptance-write failure exposes staged pending message; same draft ID retries exact ciphertext once");
    }

    static void cancelAfterStage() throws Exception {
        Fixture f = new Fixture("demo-account-cancel-stage");
        f.enroll();
        SignalClient bob = peer("demo-account-cancel-bob", "demo-device-cancel-bob");
        f.open(ROOM, bob, false);
        f.verify();
        String messageId = "demo-message-background-cancel";
        String text = "synthetic background retry";
        boolean[] staged = {false};
        Completion canceled = new Completion();
        f.coordinator.sendText(ROOM, messageId, text, () -> {
            staged[0] = true;
            f.coordinator.cancel();
        }, canceled);
        TorApiClient.Request claim = f.api.pop();
        claim.callback.complete(200, bundle(claim, bob), "");
        while (!staged[0]) Handler.pump();
        SignalClient.Envelope durable = f.local().pendingOutbox().get(0);
        check(durable.messageId.equals(messageId), "staged ID differs from the native draft ID");
        check(canceled.result == null, "canceled generation invoked its completion");
        check(f.api.requests.isEmpty(), "background cancellation sent a request after staging");

        Completion resumed = new Completion();
        int[] checkpoints = {0};
        f.coordinator.sendText(ROOM, messageId, text, () -> checkpoints[0]++, resumed);
        TorApiClient.Request retry = f.api.pop();
        check(retry.body.getString("message_id").equals(messageId), "resume changed message ID");
        check(retry.body.getJSONObject("envelope").getString("ciphertext_base64").equals(durable.wireBase64()),
                "resume re-encrypted the same draft");
        check(f.local().sentMessages().size() == 1, "resume duplicated durable outbox");
        check(checkpoints[0] == 1, "resume omitted durable checkpoint");
        check(f.coordinator.messages().length() == 1, "resume omitted pending message from view");
        accept(retry, ROOM);
        resumed.await("");
        check(canceled.result == null, "resumption completed the canceled operation");
        check(f.local().pendingOutbox().isEmpty(), "resumed acceptance not committed");
        f.coordinator.close();
        System.out.println("PASS cancellation at durable staging suppresses HTTP; native draft ID resumes exact one-record ciphertext");
    }

    static void accept(TorApiClient.Request request, String room) throws Exception {
        JSONObject envelope = request.body.getJSONObject("envelope");
        request.callback.complete(200, json(
                "message_id", request.body.getString("message_id"),
                "room_id", room,
                "sender_id", envelope.getString("sender_id"),
                "recipient_id", envelope.getString("recipient_id"),
                "status", "accepted"), "");
    }
    static void ownedHistory(TorApiClient api, JSONArray history) throws Exception {
        TorApiClient.Request request = api.pop();
        check(request.method.equals("GET") && request.path.equals("/v1/devices"), "expected own device history first");
        request.callback.complete(200, json("devices", history), "");
    }

    static void refillRetryRestart() throws Exception {
        String account = "demo-account-refill-restart";
        Fixture f = new Fixture(account);
        f.enroll();
        Completion first = new Completion();
        f.coordinator.maintainPrekeys(first);
        TorApiClient.Request status = f.api.pop();
        check(status.method.equals("GET") && status.path.endsWith("/prekeys"), "expected prekey status");
        status.callback.complete(200, json("device_id", f.session.deviceId, "remaining_pairs", 8), "");
        TorApiClient.Request submitted = f.api.pop();
        check(submitted.path.endsWith("/prekeys/replenish"), "missing replenishment request");
        check(submitted.body.getJSONArray("one_time_prekeys").length() == 12, "low-water refill did not fill to twenty");
        SignalClient.Replenishment persisted = f.local().pendingReplenishment();
        check(persisted != null && SignalJson.replenishment(persisted).toString().equals(submitted.body.toString()),
                "refill was not durable before HTTP");
        submitted.callback.complete(0, new JSONObject(), "TIMEOUT");
        first.await("TIMEOUT");
        check(f.local().pendingReplenishment() != null, "lost HTTP response cleared pending refill");
        f.coordinator.close();
        dropProfileOwner(account);

        Fixture restored = new Fixture(account);
        restored.enroll();
        Completion malformed = new Completion();
        restored.coordinator.maintainPrekeys(malformed);
        restored.api.pop().callback.complete(200, json("device_id", restored.session.deviceId, "remaining_pairs", 20), "");
        TorApiClient.Request replay = restored.api.pop();
        check(replay.body.toString().equals(submitted.body.toString()), "restart/full-pool changed pending batch bytes");
        replay.callback.complete(200, json("device_id", "demo-wrong-device", "remaining_pairs", 20), "");
        malformed.await("INVALID_PREKEY_STATUS");
        check(restored.local().pendingReplenishment() != null, "malformed success confirmed refill");

        Completion failCommit = new Completion();
        restored.coordinator.maintainPrekeys(failCommit);
        restored.api.pop().callback.complete(200, json("device_id", restored.session.deviceId, "remaining_pairs", 20), "");
        TorApiClient.Request confirmed = restored.api.pop();
        check(confirmed.body.toString().equals(submitted.body.toString()), "retry after malformed response changed batch");
        KeystoreBlobStore.failNamespace = SignalProfile.namespace(ORIGIN, account);
        KeystoreBlobStore.failures = 1;
        confirmed.callback.complete(200, json("device_id", restored.session.deviceId, "remaining_pairs", 20), "");
        failCommit.await("CRYPTO_OR_STORAGE_FAILED");
        check(restored.local().pendingReplenishment() != null, "failed confirmation write lost batch");

        Completion success = new Completion();
        restored.coordinator.maintainPrekeys(success);
        restored.api.pop().callback.complete(200, json("device_id", restored.session.deviceId, "remaining_pairs", 20), "");
        TorApiClient.Request finalReplay = restored.api.pop();
        check(finalReplay.body.toString().equals(submitted.body.toString()), "confirm-write retry changed batch");
        finalReplay.callback.complete(200, json("device_id", restored.session.deviceId, "remaining_pairs", 20), "");
        success.await("");
        check(restored.local().pendingReplenishment() == null, "successful confirmation did not clear pending batch");
        Completion aboveLowWater = new Completion();
        restored.coordinator.maintainPrekeys(aboveLowWater);
        restored.api.pop().callback.complete(200, json("device_id", restored.session.deviceId, "remaining_pairs", 9), "");
        aboveLowWater.await("");
        check(restored.api.requests.isEmpty(), "refilled above the low-water threshold without a pending batch");
        restored.coordinator.close();
        System.out.println("PASS refill survives lost response/owner restart; full-pool retries exact batch; only valid durable confirmation clears it");
    }

    static void dropProfileOwner(String account) throws Exception {
        java.lang.reflect.Field field = MessageCoordinator.class.getDeclaredField("profiles");
        field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).remove(SignalProfile.namespace(ORIGIN, account));
    }

    // Synthetic-only deterministic provider: force the first available protocol ID during this one test.
    // It is never packaged in Android; no key or credential generated here leaves process memory.
    public static final class ZeroRandom extends java.security.SecureRandomSpi {
        protected void engineSetSeed(byte[] seed) { }
        protected void engineNextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) 0); }
        protected byte[] engineGenerateSeed(int count) { return new byte[count]; }
    }

    static void historicalProtocolIds() throws Exception {
        Fixture f = new Fixture("demo-account-history");
        Completion done = new Completion();
        f.coordinator.enroll(done);
        JSONArray history = new JSONArray();
        for (int protocol = 1; protocol <= 4; protocol++) {
            history.put(json("account_id", f.session.accountId, "device_id", "demo-device-history-" + protocol,
                    "protocol_device_id", protocol, "revoked", true));
        }
        java.security.Provider synthetic = new java.security.Provider("CoordinatorSyntheticRandom", "1", "Memory-only coordinator fixture") { };
        synthetic.put("SecureRandom.CoordinatorSyntheticRandom", ZeroRandom.class.getName());
        TorApiClient.Request registration;
        java.security.Security.insertProviderAt(synthetic, 1);
        try {
            ownedHistory(f.api, history);
            registration = f.api.pop();
            check(f.local().protocolDeviceId() == 5, "history exclusions were not applied before identity generation");
        } finally {
            java.security.Security.removeProvider(synthetic.getName());
        }
        f.register(registration);
        TorApiClient.Request publication = f.api.pop();
        check(publication.body.getInt("protocol_device_id") == 5, "publication changed the selected noncolliding protocol ID");
        publication.callback.complete(200, json("device_id", f.session.deviceId, "remaining_pairs", 20), "");
        done.await("");
        f.coordinator.close();
        System.out.println("PASS historical protocol IDs 1–4 are excluded deterministically before first identity publication");
    }

    static void rotatePeerIdentity(boolean sameDeviceId) throws Exception { rotatePeerIdentity(sameDeviceId,false); }
    static void rotatePeerIdentity(boolean sameDeviceId,boolean alreadyAccepted) throws Exception {
        Fixture f = new Fixture(alreadyAccepted ? "demo-account-rotation-accepted" : sameDeviceId ? "demo-account-rotation-same-device" : "demo-account-rotation");
        f.enroll();
        SignalClient oldPeer = peer("demo-account-rotated-peer", "demo-device-old-peer");
        f.open(ROOM, oldPeer, false);
        f.verify();
        Completion oldSend = new Completion();
        f.coordinator.sendText(ROOM, "demo-message-rotation-old", "old synthetic pending text", () -> { }, oldSend);
        TorApiClient.Request claim = f.api.pop();
        claim.callback.complete(200, bundle(claim, oldPeer), "");
        TorApiClient.Request oldWire = f.api.pop();
        if(alreadyAccepted){accept(oldWire,ROOM);oldSend.await("");}
        else {oldWire.callback.complete(0, new JSONObject(), "TIMEOUT");oldSend.await("QUEUED_TIMEOUT");}
        String oldCiphertext = oldWire.body.getJSONObject("envelope").getString("ciphertext_base64");
        String oldIdentity = oldPeer.identityKeyBase64();
        SignalClient alice = f.local();
        oldPeer.acceptPeer(ROOM, alice.accountId(), alice.deviceId(), alice.protocolDeviceId(), alice.identityKeyBase64());
        oldPeer.receive(alice.sentMessages().get(0).envelope);
        SignalClient.Envelope reply = oldPeer.stageText(ROOM, alice.accountId(), alice.deviceId(),
                "demo-message-old-peer-reply", "retained synthetic incoming text");
        JSONObject receipt = json("message_id", reply.messageId, "room_id", ROOM,
                "sender_id", oldPeer.accountId(), "recipient_id", alice.accountId());
        Completion received = new Completion();
        f.coordinator.poll(ROOM, received);
        f.api.pop().callback.complete(200, json("receipts", new JSONArray().put(receipt)), "");
        f.api.pop().callback.complete(200, json("receipt", receipt,
                "envelope", SignalJson.sendRequest(oldPeer, reply).getJSONObject("envelope")), "");
        f.api.pop().callback.complete(200, json("message_id", reply.messageId,
                "recipient_id", alice.accountId(), "status", "recipient-acknowledged"), "");
        received.await("");

        SignalClient newPeer = peer(oldPeer.accountId(), sameDeviceId ? oldPeer.deviceId() : "demo-device-new-peer");
        f.open(ROOM, newPeer, false);
        MessageCoordinator.Peer replacement = f.coordinator.pendingPeer();
        check(replacement.oldIdentityKeyBase64.equals(oldIdentity), "old identity missing from replacement approval");
        check(replacement.oldFingerprint.equals(SignalJson.keyDigest(oldIdentity)), "old fingerprint missing");
        check(replacement.fingerprint.equals(SignalJson.keyDigest(newPeer.identityKeyBase64())), "new fingerprint missing");
        Completion ordinaryVerify = new Completion();
        f.coordinator.verifyPeer(replacement, ordinaryVerify);
        ordinaryVerify.await("PEER_REPLACEMENT_REQUIRED");
        check(f.local().pinnedIdentityKeyBase64(oldPeer.accountId()).equals(oldIdentity), "ordinary verification changed old pin");
        Completion failedRotation = new Completion();
        KeystoreBlobStore.failNamespace = SignalProfile.namespace(ORIGIN, f.session.accountId);
        KeystoreBlobStore.failures = 1;
        f.coordinator.replacePeerIdentity(replacement, failedRotation);
        failedRotation.await("CRYPTO_OR_STORAGE_FAILED");
        check(f.local().pinnedIdentityKeyBase64(oldPeer.accountId()).equals(oldIdentity), "failed rotation changed the old pin");
        Completion rotate = new Completion();
        f.coordinator.replacePeerIdentity(replacement, rotate);
        rotate.await("");
        check(f.local().pinnedIdentityKeyBase64(oldPeer.accountId()).equals(newPeer.identityKeyBase64()), "explicit replacement did not install new pin");
        check(f.coordinator.messages().length() == 2, "rotation erased old conversation history");
        check(f.local().inbox().size() == 1 && f.local().inbox().get(0).text.equals("retained synthetic incoming text"),
                "rotation erased received plaintext history");
        check(f.coordinator.messages().getJSONObject(0).getString("status").equals(alreadyAccepted?"server_accepted":"retired_identity"), "accepted history or retired pending status changed incorrectly");
        check(f.local().sentMessages().get(0).envelope.wireBase64().equals(oldCiphertext), "rotation rewrote old ciphertext");
        Completion oldRetry = new Completion();
        f.coordinator.retryPending(ROOM, oldRetry);
        oldRetry.await("");
        check(f.api.requests.isEmpty(), "old-device outbox replayed after rotation");
        check(f.local().pendingOutbox().isEmpty(), "retired ciphertext remains in the retryable outbox");
        check(f.local().sentMessages().get(0).retired, "retirement flag was not durably loaded");
        if (sameDeviceId || alreadyAccepted) {
            f.coordinator.close();
            System.out.println(alreadyAccepted?"PASS accepted history keeps server_accepted after identity retirement without allowing replay":"PASS same-device-ID identity rotation durably retires old ciphertext and shows retired_identity");
            return;
        }

        Completion newSend = new Completion();
        f.coordinator.sendText(ROOM, "demo-message-rotation-new", "new synthetic text", () -> { }, newSend);
        TorApiClient.Request newClaim = f.api.pop();
        check(newClaim.body.getString("recipient_device_id").equals(newPeer.deviceId()), "rotation claimed old recipient device");
        newClaim.callback.complete(200, bundle(newClaim, newPeer), "");
        TorApiClient.Request newWire = f.api.pop();
        check(newWire.body.getJSONObject("envelope").getString("recipient_device_id").equals(newPeer.deviceId()), "new ciphertext targets old device");
        check(newWire.body.getJSONObject("envelope").getString("recipient_key_digest").equals(SignalJson.keyDigest(newPeer.identityKeyBase64())), "new wire has old identity digest");
        accept(newWire, ROOM);
        newSend.await("");
        check(f.local().sentMessages().size() == 2, "rotation lost old history or duplicated new send");
        f.coordinator.close();
        System.out.println("PASS explicit old/new pin rotation preserves history, blocks old-device ciphertext, and sends under the new identity");
    }

    static void roomPeerConfusion()throws Exception {
        Fixture f=new Fixture("demo-account-binding");
        f.enroll();
        SignalClient charlie=peer("demo-account-charlie","demo-device-charlie");
        f.open("demo-room-charlie",charlie,false);
        f.verify();
        Completion denied=new Completion();
        f.coordinator.openRoom("demo-room-bob",BOB,denied);
        f.api.pop().callback.complete(200,directory(charlie),"");
        denied.await("INVALID_PEER_DEVICE");
        check(!f.coordinator.isReady("demo-room-bob"),"substituted pinned peer accepted");
        f.coordinator.close();
        System.out.println("PASS wrong already-pinned peer rejected by expected-account binding patch");
    }
    static void staleGeneration()throws Exception {
        Fixture f=new Fixture("demo-account-stale");
        Completion old=new Completion();
        f.coordinator.enroll(old);
        ownedHistory(f.api, new JSONArray());
        TorApiClient.Request registration=f.api.pop();
        String other="demo-account-new";
        f.coordinator.bindSession(new AppSessionStore.Session(ORIGIN,other,token("gd_acct_"),"","",token("gd_sess_")));
        f.register(registration);
        check(old.result==null&&f.api.requests.isEmpty(),"stale callback escaped");
        check(!KeystoreBlobStore.DATA.containsKey(SignalProfile.namespace(ORIGIN,other)),"new profile got old keys");
        f.coordinator.close();
        System.out.println("PASS old enrollment HTTP response cannot mutate rebound profile or callback");
    }
    static final String OTHER_ORIGIN="http://"+"b".repeat(56)+".onion";
    static SignalClient initializeLocal(Fixture f)throws Exception {
        SignalClient local=f.local();
        local.initialize(f.session.serverOnion,f.session.accountId,"demo-device-archive",20);
        return local;
    }
    static void bindPeer(SignalClient local,String room,SignalClient other)throws Exception {
        local.acceptPeer(room,other.accountId(),other.deviceId(),other.protocolDeviceId(),other.identityKeyBase64());
        if(!local.hasSession(other.accountId(),other.deviceId()))
            local.acceptBundle(room,other.accountId(),other.deviceId(),other.publication().bundle(0));
    }
    static SignalClient.Envelope stage(SignalClient local,String room,SignalClient other,String id)throws Exception {
        bindPeer(local,room,other);
        return local.stageText(room,other.accountId(),other.deviceId(),id,"synthetic archive "+id);
    }
    static LocalRoomsCompletion list(Fixture f,String peer,String after,int limit)throws Exception {
        LocalRoomsCompletion result=new LocalRoomsCompletion();
        f.coordinator.listLocalRooms(peer,after,limit,result);result.await("");return result;
    }
    static void openArchive(Fixture f,String room,String peer)throws Exception {
        Completion done=new Completion();f.coordinator.openLocalRoom(room,peer,done);done.await("");
        check(f.coordinator.isLocalArchive(room)&&f.coordinator.currentRoom().equals(room),"archive selection missing");
        check(!f.coordinator.isReady(room),"archive became send-ready");
    }
    static Map<String,byte[]> stateSnapshot() {
        Map<String,byte[]> snapshot=new HashMap<>();
        synchronized(KeystoreBlobStore.DATA) {
            for(Map.Entry<String,byte[]> entry:KeystoreBlobStore.DATA.entrySet())snapshot.put(entry.getKey(),entry.getValue().clone());
        }
        return snapshot;
    }
    static void unchanged(Map<String,byte[]> before,String reason) {
        synchronized(KeystoreBlobStore.DATA) {
            check(before.keySet().equals(KeystoreBlobStore.DATA.keySet()),reason+": profile namespaces changed");
            for(Map.Entry<String,byte[]> entry:before.entrySet())
                check(Arrays.equals(entry.getValue(),KeystoreBlobStore.DATA.get(entry.getKey())),reason+": encrypted state changed");
        }
    }
    static ExecutorService cryptoWorker()throws Exception {
        java.lang.reflect.Field field=MessageCoordinator.class.getDeclaredField("CRYPTO_WORKER");
        field.setAccessible(true);return (ExecutorService)field.get(null);
    }
    static void workerFinished()throws Exception { cryptoWorker().submit(()->{}).get(10,TimeUnit.SECONDS); }
    static void drainMain() { Runnable next;while((next=Handler.QUEUE.poll())!=null)next.run(); }

    static void offlineArchiveDiscovery()throws Exception {
        Fixture f=new Fixture("demo-account-archive-offline");
        SignalClient local=initializeLocal(f),bob=peer(BOB,"demo-device-archive-bob");
        SignalClient charlie=peer("demo-account-archive-charlie","demo-device-archive-charlie");
        stage(local,ROOM,bob,"demo-message-offline-local");
        stage(local,"demo-room-other-local",charlie,"demo-message-other-local");
        Map<String,byte[]> before=stateSnapshot();
        KeystoreBlobStore.failNamespace=SignalProfile.namespace(ORIGIN,f.session.accountId);
        KeystoreBlobStore.failures=1;
        try {
            JSONObject first=list(f,"","",1).page;
            JSONArray rooms=first.getJSONArray("rooms");
            check(rooms.length()==1,"archive room page limit ignored");
            JSONObject room=rooms.getJSONObject(0);
            check(room.getString("room_id").equals(ROOM)&&room.getString("peer_id").equals(BOB),"archive room/peer metadata changed");
            check(room.getInt("message_count")==1&&room.getLong("last_message_at")>0&&room.getBoolean("read_only"),"archive metadata incomplete");
            check(first.getString("next_after").equals(ROOM),"archive discovery cursor missing");
            JSONObject second=list(f,"",first.getString("next_after"),1).page;
            check(second.getJSONArray("rooms").length()==1&&second.getJSONArray("rooms").getJSONObject(0).getString("peer_id").equals(charlie.accountId()),"room paging skipped next peer");
            check(second.getString("next_after").isEmpty(),"terminal room cursor not empty");
            check(list(f,BOB,"",40).page.getJSONArray("rooms").length()==1,"peer-filtered room discovery leaked another peer");
            check(list(f,"demo-account-no-local-peer","",40).page.getJSONArray("rooms").length()==0,"unknown peer discovery was not empty");
            check(f.coordinator.currentRoom().isEmpty(),"listing selected a room");
            openArchive(f,ROOM,BOB);
            JSONArray messages=f.coordinator.messagesForRoom(ROOM);
            check(messages.length()==1&&messages.getJSONObject(0).getString("id").equals("demo-message-offline-local"),"offline archive hid committed plaintext");
            check(f.coordinator.messagesForRoom("demo-room-other-local").length()==0,"archive view leaked into another room");
            messages.getJSONObject(0).put("text","tampered view copy");
            check(!f.coordinator.messagesForRoom(ROOM).getJSONObject(0).getString("text").equals("tampered view copy"),"caller mutated archive cache");
            check(f.session.deviceId.isEmpty()&&f.session.deviceToken.isEmpty(),"archive enrolled a device");
            check(f.api.requests.isEmpty(),"offline archive sent HTTP");
            unchanged(before,"offline list/open");
            check(KeystoreBlobStore.failures==1,"archive attempted a state write");
        } finally { KeystoreBlobStore.failures=0;KeystoreBlobStore.failNamespace=null;f.coordinator.close(); }
        System.out.println("PASS offline archive discovery/open needs no enrolled device; pages and views preserve encrypted keys, ratchets and all state without HTTP");
    }

    static void archiveScopeAndValidation()throws Exception {
        Fixture empty=new Fixture("demo-account-archive-empty");
        Map<String,byte[]> emptyBefore=stateSnapshot();
        JSONObject page=list(empty,"","",40).page;
        check(page.getJSONArray("rooms").length()==0&&page.getString("next_after").isEmpty(),"empty profile archive was not empty");
        Completion unknown=new Completion();empty.coordinator.openLocalRoom(ROOM,BOB,unknown);unknown.await("LOCAL_ROOM_NOT_FOUND");
        unchanged(emptyBefore,"empty archive");check(empty.api.requests.isEmpty(),"empty archive enrolled or contacted server");empty.coordinator.close();

        Fixture f=new Fixture("demo-account-archive-scope");
        SignalClient local=initializeLocal(f),bob=peer(BOB,"demo-device-archive-scope");
        stage(local,ROOM,bob,"demo-message-archive-scope");
        Map<String,byte[]> before=stateSnapshot();
        Completion wrongPeer=new Completion();f.coordinator.openLocalRoom(ROOM,"demo-account-wrong-peer",wrongPeer);wrongPeer.await("LOCAL_ROOM_NOT_FOUND");
        check(f.coordinator.messages().length()==0,"wrong expected peer exposed history");
        for(int limit:new int[]{0,41}) {
            LocalRoomsCompletion invalid=new LocalRoomsCompletion();f.coordinator.listLocalRooms("","",limit,invalid);invalid.await("INVALID_LOCAL_REQUEST");
        }
        for(int limit:new int[]{0,201}) {
            Completion invalid=new Completion();f.coordinator.openLocalRoom(ROOM,BOB,"",limit,invalid);invalid.await("INVALID_LOCAL_REQUEST");
        }
        Completion badCursor=new Completion();f.coordinator.openLocalRoom(ROOM,BOB,"not-an-archive-cursor",2,badCursor);badCursor.await("INVALID_LOCAL_REQUEST");
        f.api.origin=OTHER_ORIGIN;
        LocalRoomsCompletion wrongOrigin=new LocalRoomsCompletion();f.coordinator.listLocalRooms("","",40,wrongOrigin);wrongOrigin.await("ORIGIN_MISMATCH");
        Completion wrongOpenOrigin=new Completion();f.coordinator.openLocalRoom(ROOM,BOB,wrongOpenOrigin);wrongOpenOrigin.await("ORIGIN_MISMATCH");
        unchanged(before,"invalid archive selection");check(f.api.requests.isEmpty(),"invalid archive selection sent HTTP");
        f.coordinator.close();

        for(boolean changeOrigin:new boolean[]{false,true}) {
            String account=changeOrigin?f.session.accountId:"demo-account-archive-foreign";
            String origin=changeOrigin?OTHER_ORIGIN:ORIGIN;
            Fixture isolated=new Fixture(account);
            isolated.session=new AppSessionStore.Session(origin,account,token("gd_acct_"),"","",token("gd_sess_"));
            isolated.api.origin=origin;isolated.coordinator.bindSession(isolated.session);
            check(list(isolated,"","",40).page.getJSONArray("rooms").length()==0,"another profile exposed old archive");
            String source=SignalProfile.namespace(ORIGIN,f.session.accountId),destination=SignalProfile.namespace(origin,account);
            // Fault injection: even bytes copied into the wrong namespace cannot cross the stored profile binding.
            synchronized(KeystoreBlobStore.DATA) { KeystoreBlobStore.DATA.put(destination,KeystoreBlobStore.DATA.get(source).clone()); }
            Map<String,byte[]> foreignBefore=stateSnapshot();
            LocalRoomsCompletion mismatched=new LocalRoomsCompletion();isolated.coordinator.listLocalRooms("","",40,mismatched);mismatched.await("LOCAL_PROFILE_MISMATCH");
            check(mismatched.page==null||mismatched.page.optJSONArray("rooms")==null||mismatched.page.getJSONArray("rooms").length()==0,"foreign stored profile leaked room metadata");
            Completion mismatchedOpen=new Completion();isolated.coordinator.openLocalRoom(ROOM,BOB,mismatchedOpen);mismatchedOpen.await("LOCAL_PROFILE_MISMATCH");
            check(isolated.coordinator.messages().length()==0&&isolated.api.requests.isEmpty(),"mismatched encrypted profile exposed plaintext or sent HTTP");
            unchanged(foreignBefore,"foreign archive profile");isolated.coordinator.close();
        }
        System.out.println("PASS empty/missing archives never initialize keys; wrong peers, accounts, origins, stored profiles, page limits and cursors fail closed");
    }

    static void boundedArchiveHistory()throws Exception {
        Fixture f=new Fixture("demo-account-archive-pages");
        SignalClient local=initializeLocal(f),bob=peer(BOB,"demo-device-archive-pages");
        bindPeer(local,ROOM,bob);
        for(int i=0;i<203;i++)local.stageText(ROOM,BOB,bob.deviceId(),String.format(Locale.ROOT,"demo-message-page-%03d",i),"synthetic page "+i);
        stage(local,"demo-room-page-other",bob,"demo-message-page-other");
        Map<String,byte[]> before=stateSnapshot();openArchive(f,ROOM,BOB);
        JSONArray newest=f.coordinator.messages();
        check(newest.length()==200&&newest.getJSONObject(0).getString("id").equals("demo-message-page-003")
                &&newest.getJSONObject(199).getString("id").equals("demo-message-page-202"),"default history did not retain latest 200 in order");
        String cursor=f.coordinator.localArchiveNextBefore();check(!cursor.isEmpty(),"older history cursor missing");
        Completion older=new Completion();f.coordinator.openLocalRoom(ROOM,BOB,cursor,200,older);older.await("");
        JSONArray oldest=f.coordinator.messages();check(oldest.length()==3,"older page lost or duplicated records");
        for(int i=0;i<3;i++)check(oldest.getJSONObject(i).getString("id").equals(String.format(Locale.ROOT,"demo-message-page-%03d",i)),"older page ordering changed");
        check(f.coordinator.localArchiveNextBefore().isEmpty(),"terminal message cursor not empty");
        Completion small=new Completion();f.coordinator.openLocalRoom(ROOM,BOB,"",1,small);small.await("");
        check(f.coordinator.messages().length()==1&&f.coordinator.messages().getJSONObject(0).getString("id").equals("demo-message-page-202"),"minimum history limit ignored");
        String smallCursor=f.coordinator.localArchiveNextBefore();
        Completion next=new Completion();f.coordinator.openLocalRoom(ROOM,BOB,smallCursor,2,next);next.await("");
        check(f.coordinator.messages().length()==2&&f.coordinator.messages().getJSONObject(0).getString("id").equals("demo-message-page-200")
                &&f.coordinator.messages().getJSONObject(1).getString("id").equals("demo-message-page-201"),"history cursor was not exclusive");
        Completion foreignCursor=new Completion();f.coordinator.openLocalRoom("demo-room-page-other",BOB,smallCursor,2,foreignCursor);foreignCursor.await("INVALID_LOCAL_REQUEST");
        unchanged(before,"bounded history pages");check(f.api.requests.isEmpty(),"history pagination contacted server");f.coordinator.close();
        System.out.println("PASS archive defaults to latest 200; bounded older pages are ordered and exclusive; foreign-room cursors cannot reveal history");
    }

    interface ArchiveOperation { void run(Completion done); }
    static void archiveOperationDenied(Fixture f,String label,ArchiveOperation action)throws Exception {
        Completion denied=new Completion();action.run(denied);denied.await("LOCAL_ARCHIVE_READ_ONLY");
        check(f.coordinator.isLocalArchive(ROOM)&&!f.coordinator.isReady(ROOM),label+" escaped the archive guard");
        check(f.api.requests.isEmpty(),label+" sent HTTP from archive");
    }
    static void archiveCannotBecomeLive()throws Exception {
        Fixture f=new Fixture("demo-account-archive-guard");f.enroll();
        SignalClient bob=peer(BOB,"demo-device-archive-guard");
        stage(f.local(),ROOM,bob,"demo-message-archive-guard");f.open(ROOM,bob,true);
        check(f.coordinator.isReady(ROOM),"live fixture not ready");MessageCoordinator.Peer selected=f.coordinator.pendingPeer();
        Map<String,byte[]> before=stateSnapshot();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        cryptoWorker().execute(()->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("archive-load gate timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}});
        check(entered.await(10,TimeUnit.SECONDS),"archive-load gate not entered");
        Completion loading=new Completion();
        try {
            f.coordinator.openLocalRoom(ROOM,BOB,loading);
            Completion blockedWhileLoading=new Completion();f.coordinator.poll(ROOM,blockedWhileLoading);
            blockedWhileLoading.await("LOCAL_ARCHIVE_READ_ONLY");
            check(loading.result==null&&!f.coordinator.isReady(ROOM)&&f.api.requests.isEmpty(),"archive loading retained live authorization");
        } finally { release.countDown(); }
        loading.await("");check(f.coordinator.isLocalArchive(ROOM),"archive load did not finish");
        archiveOperationDenied(f,"enrollment",done->f.coordinator.enroll(done));
        archiveOperationDenied(f,"prekey maintenance",done->f.coordinator.maintainPrekeys(done));
        archiveOperationDenied(f,"open room",done->f.coordinator.openRoom(ROOM,BOB,done));
        archiveOperationDenied(f,"legacy open room",done->f.coordinator.openRoom(ROOM,done));
        archiveOperationDenied(f,"verification",done->f.coordinator.verifyPeer(selected,done));
        archiveOperationDenied(f,"identity replacement",done->f.coordinator.replacePeerIdentity(selected,done));
        archiveOperationDenied(f,"send",done->f.coordinator.sendText(ROOM,"blocked synthetic send",done));
        boolean[] staged={false};
        archiveOperationDenied(f,"explicit draft send",done->f.coordinator.sendText(ROOM,"demo-message-archive-blocked","blocked synthetic draft",()->staged[0]=true,done));
        archiveOperationDenied(f,"retry",done->f.coordinator.retryPending(ROOM,done));
        archiveOperationDenied(f,"poll",done->f.coordinator.poll(ROOM,done));
        check(!staged[0],"archive invoked durable staging callback");
        f.coordinator.cancel();check(f.coordinator.isLocalArchive(ROOM)&&!f.coordinator.isReady(ROOM),"cancellation made archive live");
        check(list(f,"","",40).page.getJSONArray("rooms").length()==1,"archive listing unavailable while archive selected");
        check(!f.coordinator.isReady(ROOM),"listing made archive live");unchanged(before,"archive mutation guards");
        f.coordinator.leaveLocalArchive();
        check(!f.coordinator.isLocalArchive(ROOM)&&f.coordinator.currentRoom().isEmpty()&&f.coordinator.messages().length()==0
                &&f.coordinator.localArchiveNextBefore().isEmpty()&&!f.coordinator.isReady(ROOM),"leaving archive reused live authorization or plaintext view");
        f.open(ROOM,bob,true);check(f.coordinator.isReady(ROOM),"explicit live-room path could not resume");f.coordinator.close();
        System.out.println("PASS archive blocks enrollment, prekeys, room open, verify, identity replacement, send, retry and poll before state/network changes; live mode requires leaving and opening again");
    }

    static void retiredIdentityArchive()throws Exception {
        Fixture f=new Fixture("demo-account-archive-retired");SignalClient local=initializeLocal(f);
        SignalClient oldPeer=peer(BOB,"demo-device-archive-retired-old");
        SignalClient.Envelope accepted=stage(local,ROOM,oldPeer,"demo-message-archive-accepted");local.markRelayAccepted(accepted.messageId);
        SignalClient.Envelope pending=stage(local,ROOM,oldPeer,"demo-message-archive-retired");
        oldPeer.acceptPeer(ROOM,local.accountId(),local.deviceId(),local.protocolDeviceId(),local.identityKeyBase64());
        oldPeer.receive(accepted);oldPeer.receive(pending);
        SignalClient.Envelope incoming=oldPeer.stageText(ROOM,local.accountId(),local.deviceId(),"demo-message-archive-incoming","synthetic old identity reply");local.receive(incoming);
        SignalClient replacement=peer(BOB,"demo-device-archive-retired-new");
        local.replacePeerIdentity("demo-room-archive-new",BOB,replacement.deviceId(),replacement.protocolDeviceId(),replacement.identityKeyBase64(),oldPeer.identityKeyBase64());
        check(!local.hasSession(BOB,replacement.deviceId()),"rotation fixture retained session");
        Map<String,byte[]> before=stateSnapshot();
        JSONArray rooms=list(f,BOB,"",40).page.getJSONArray("rooms");
        boolean found=false;for(int i=0;i<rooms.length();i++)if(rooms.getJSONObject(i).getString("room_id").equals(ROOM)) {
            found=true;check(rooms.getJSONObject(i).getInt("message_count")==3,"retired room history count changed");
        }
        check(found,"identity replacement hid unbound historical room");openArchive(f,ROOM,BOB);
        Map<String,String> statuses=new HashMap<>();JSONArray history=f.coordinator.messages();
        for(int i=0;i<history.length();i++)statuses.put(history.getJSONObject(i).getString("id"),history.getJSONObject(i).getString("status"));
        check(statuses.size()==3&&"server_accepted".equals(statuses.get(accepted.messageId))
                &&"retired_identity".equals(statuses.get(pending.messageId))&&"received_locally".equals(statuses.get(incoming.messageId)),"archive lost retired/accepted/incoming status distinctions");
        check(!local.hasSession(BOB,replacement.deviceId())&&local.pendingOutbox().isEmpty(),"archive reconstructed sessions or resurrected retired outbox");
        unchanged(before,"retired identity archive");check(f.api.requests.isEmpty(),"retired archive contacted server");f.coordinator.close();
        System.out.println("PASS identity replacement leaves unbound local room discoverable with accepted, retired and incoming history; archive never restores sessions or retryable ciphertext");
    }

    static void archiveSuppressesLateHttp()throws Exception {
        Fixture f=new Fixture("demo-account-archive-http");f.enroll();SignalClient bob=peer(BOB,"demo-device-archive-http");
        SignalClient local=f.local();SignalClient.Envelope prior=stage(local,ROOM,bob,"demo-message-archive-http-prior");f.open(ROOM,bob,true);
        bob.acceptPeer(ROOM,local.accountId(),local.deviceId(),local.protocolDeviceId(),local.identityKeyBase64());bob.receive(prior);
        SignalClient.Envelope incoming=bob.stageText(ROOM,local.accountId(),local.deviceId(),"demo-message-archive-http-late","synthetic delayed ciphertext");
        JSONObject receipt=json("message_id",incoming.messageId,"room_id",ROOM,"sender_id",BOB,"recipient_id",local.accountId());
        Completion oldPoll=new Completion();f.coordinator.poll(ROOM,oldPoll);
        f.api.pop().callback.complete(200,json("receipts",new JSONArray().put(receipt)),"");TorApiClient.Request delayedFetch=f.api.pop();
        Map<String,byte[]> before=stateSnapshot();openArchive(f,ROOM,BOB);
        delayedFetch.callback.complete(200,json("receipt",receipt,"envelope",SignalJson.sendRequest(bob,incoming).getJSONObject("envelope")),"");
        workerFinished();drainMain();
        check(oldPoll.result==null&&f.api.requests.isEmpty(),"late inbox fetch escaped archive with callback or ACK");
        check(f.coordinator.messages().length()==1&&!f.coordinator.isReady(ROOM)&&local.inbox().isEmpty(),"late inbox fetch advanced receive state or live readiness");unchanged(before,"late archive inbox response");
        f.coordinator.leaveLocalArchive();f.open(ROOM,bob,true);
        Completion oldSend=new Completion();f.coordinator.sendText(ROOM,"demo-message-archive-http-pending","synthetic delayed acceptance",()->{},oldSend);
        TorApiClient.Request delayedAcceptance=f.api.pop();Map<String,byte[]> pendingBefore=stateSnapshot();openArchive(f,ROOM,BOB);
        accept(delayedAcceptance,ROOM);workerFinished();drainMain();
        check(oldSend.result==null&&f.api.requests.isEmpty(),"late send acceptance escaped archive generation");
        check(local.pendingOutbox().size()==2&&!f.coordinator.isReady(ROOM),"late acceptance committed into archive");unchanged(pendingBefore,"late archive send response");
        f.coordinator.close();System.out.println("PASS archive selection supersedes pending inbox/send HTTP; delayed responses cannot ACK, commit, invoke stale callbacks or restore readiness");
    }

    static void staleArchiveWorkers()throws Exception {
        for(boolean listOnly:new boolean[]{false,true})for(boolean beforeRead:new boolean[]{true,false})for(int change=0;change<3;change++) {
            Fixture f=new Fixture("demo-account-archive-stale-"+(listOnly?"list":"open")+"-"+(beforeRead?"before":"after")+"-"+change);
            SignalClient local=initializeLocal(f),bob=peer(BOB,"demo-device-archive-stale");stage(local,ROOM,bob,"demo-message-archive-stale");
            Map<String,byte[]> before=stateSnapshot();Completion open=new Completion();LocalRoomsCompletion listing=new LocalRoomsCompletion();
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            if(beforeRead) {
                cryptoWorker().execute(()->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("worker gate timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}});
                check(entered.await(10,TimeUnit.SECONDS),"worker gate not entered");
            }
            try {
                if(listOnly)f.coordinator.listLocalRooms("","",40,listing);else f.coordinator.openLocalRoom(ROOM,BOB,open);
                if(!beforeRead)workerFinished();
                if(change==0)f.coordinator.cancel();
                else if(change==1)f.coordinator.bindSession(new AppSessionStore.Session(ORIGIN,"demo-account-archive-rebound",token("gd_acct_"),"","",token("gd_sess_")));
                else f.api.origin=OTHER_ORIGIN;
            } finally { release.countDown(); }
            workerFinished();drainMain();
            check(open.result==null&&listing.result==null,"stale archive callback survived "+(beforeRead?"before":"after")+"-read change "+change);
            check(f.coordinator.messages().length()==0&&!f.coordinator.isReady(ROOM),"stale archive worker exposed old history or readiness");
            check(f.api.requests.isEmpty(),"stale archive worker sent HTTP");unchanged(before,"stale archive worker");f.coordinator.close();
        }
        System.out.println("PASS list/open workers drop callbacks on cancel, account rebind and configured-origin changes both before read and before main-thread delivery");
    }

    public static void main(String[] args)throws Exception  {
        android.os.Looper.getMainLooper();
        if(args.length!=0)throw new IllegalArgumentException("Unknown test option");
        registrationRetry();
        pinAndOutbox();
        receiveCommit();
        acceptedCommitFailure();
        cancelAfterStage();
        roomPeerConfusion();
        staleGeneration();
        refillRetryRestart();
        historicalProtocolIds();
        rotatePeerIdentity(false);
        rotatePeerIdentity(true);
        rotatePeerIdentity(false,true);
        offlineArchiveDiscovery();
        archiveScopeAndValidation();
        boundedArchiveHistory();
        archiveCannotBecomeLive();
        retiredIdentityArchive();
        archiveSuppressesLateHttp();
        staleArchiveWorkers();
        System.out.println("PASS 19 coordinator-host scenario groups. Android Handler/Keystore/HTTP are controlled doubles; SignalClient, coordinator, JSON/schema are real. No persistent keys.");
    }
}
