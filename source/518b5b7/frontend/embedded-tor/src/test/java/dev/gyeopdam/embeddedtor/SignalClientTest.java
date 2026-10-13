package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignalClientTest {
    static final String ORIGIN = "http://" + "a".repeat(56) + ".onion";
    static final String A = "demo-account-alice", B = "demo-account-bob", AD = "demo-device-alice", BD = "demo-device-bob", ROOM = "demo-room-pair";
    static final class Memory implements SignalClient.StateStorage {
        byte[] bytes; boolean fail; int saveCalls;
        public byte[] load() { return bytes == null ? null : bytes.clone(); }
        public void save(byte[] b) throws IOException { saveCalls++; if (fail) throw new IOException("test_commit_failed"); bytes = b.clone(); }
    }
    interface Op { void run() throws Exception; }
    static void rejects(Op op) throws Exception { try { op.run(); fail("expected rejection"); } catch (Exception expected) { /* fail closed */ } }
    static final class Pair {
        final Memory as = new Memory(), bs = new Memory();
        SignalClient a = new SignalClient(as), b = new SignalClient(bs);
        Pair() throws Exception {
            a.initialize(ORIGIN, A, AD, 3); b.initialize(ORIGIN, B, BD, 3);
            pin(a, b, B, BD); pin(b, a, A, AD);
            a.acceptBundle(ROOM, B, BD, b.publication().bundle(0));
            // Receiving needs an identity pin, not a speculative reverse outgoing session.
        }
        SignalClient.Envelope send(String id, String text) throws Exception { return a.stageText(ROOM, B, BD, id, text); }
    }
    static void pin(SignalClient local, SignalClient peer, String account, String device) throws Exception {
        local.acceptPeer(ROOM, account, device, peer.protocolDeviceId(), peer.identityKeyBase64());
    }
    static SignalClient.Envelope alter(SignalClient.Envelope e, String room, String id, String sender, String senderDevice, String recipient, String recipientDevice, int type, String body) {
        return new SignalClient.Envelope(room, id, sender, senderDevice, recipient, recipientDevice, type, body);
    }
    @Test public void independentKeysAndTwoWayRealPqRatchetSurviveRestart() throws Exception {
        Pair p = new Pair(); assertNotEquals(p.a.identityKeyBase64(), p.b.identityKeyBase64());
        SignalClient.Envelope first = p.send("demo-message-first", "안녕하세요 Signal PQ");
        assertEquals(3, first.type); assertFalse(first.wireBase64().contains("안녕하세요"));
        assertEquals("안녕하세요 Signal PQ", p.b.receive(first).text);
        p.a = new SignalClient(p.as); p.b = new SignalClient(p.bs);
        SignalClient.Envelope reply = p.b.stageText(ROOM, A, AD, "demo-message-reply", "실제 암호화 답장");
        assertEquals("실제 암호화 답장", p.a.receive(reply).text);
        SignalClient.Envelope second = p.send("demo-message-second", "second");
        assertEquals(2, second.type); assertEquals("second", p.b.receive(second).text);
        assertEquals(2, p.b.inbox().size());
    }
    @Test public void explicitPinRequiredAndIdentityChangesRejected() throws Exception {
        Memory am = new Memory(), bm = new Memory(); SignalClient a = new SignalClient(am), b = new SignalClient(bm);
        a.initialize(ORIGIN, A, AD, 1); b.initialize(ORIGIN, B, BD, 1);
        rejects(() -> a.acceptBundle(ROOM, B, BD, b.publication().bundle(0)));
        pin(a, b, B, BD); a.acceptBundle(ROOM, B, BD, b.publication().bundle(0));
        SignalClient attacker = new SignalClient(new Memory()); attacker.initialize(ORIGIN, B, BD, 1);
        rejects(() -> a.acceptPeer(ROOM, B, BD, b.protocolDeviceId(), attacker.identityKeyBase64()));
        SignalClient.Bundle changed = new SignalClient.Bundle(attacker.publication().registrationId, b.protocolDeviceId(), attacker.identityKeyBase64(), attacker.publication().signedPrekey, attacker.publication().oneTimePrekeys.get(0), attacker.publication().kyberPrekeys.get(0));
        rejects(() -> a.acceptBundle(ROOM, B, BD, changed));
    }
    @Test public void signedAndKyberBundleTamperCannotCreateSession() throws Exception {
        for (boolean kyber : new boolean[]{false, true}) {
            SignalClient a = new SignalClient(new Memory()), b = new SignalClient(new Memory()); a.initialize(ORIGIN, A, AD, 1); b.initialize(ORIGIN, B, BD, 1); pin(a, b, B, BD);
            SignalClient.Bundle q = b.publication().bundle(0); SignalClient.PublicKey key = kyber ? q.kyberPrekey : q.signedPrekey;
            byte[] signature = Base64.getDecoder().decode(key.signatureBase64); signature[0] ^= 1;
            SignalClient.PublicKey bad = new SignalClient.PublicKey(key.keyId, key.publicKeyBase64, Base64.getEncoder().encodeToString(signature));
            SignalClient.Bundle tampered = new SignalClient.Bundle(q.registrationId, q.protocolDeviceId, q.identityKeyBase64, kyber ? q.signedPrekey : bad, q.oneTimePrekey, kyber ? bad : q.kyberPrekey);
            rejects(() -> a.acceptBundle(ROOM, B, BD, tampered)); assertFalse(a.hasSession(B, BD));
            a.acceptBundle(ROOM, B, BD, q); assertTrue(a.hasSession(B, BD));
        }
    }
    @Test public void retryUsesIdenticalCiphertextAndConflictingIdIsRejected() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope e = p.send("demo-message-retry", "retry");
        p.a = new SignalClient(p.as); assertEquals(e.wireBase64(), p.send(e.messageId, "retry").wireBase64());
        rejects(() -> p.send(e.messageId, "changed")); assertEquals(1, p.a.pendingOutbox().size());
        p.a.markRelayAccepted(e.messageId); assertEquals(0, p.a.pendingOutbox().size());
        assertEquals(e.wireBase64(), p.send(e.messageId, "retry").wireBase64());
    }
    @Test public void failedOutboxCommitNeverAdvancesDurableRatchet() throws Exception {
        Pair p = new Pair(); byte[] before = p.as.load(); p.as.fail = true;
        rejects(() -> p.send("demo-message-failed", "hello")); assertArrayEquals(before, p.as.load());
        p.as.fail = false; assertEquals("hello", p.b.receive(p.send("demo-message-failed", "hello")).text);
    }
    @Test public void failedInboxCommitNeverPermitsAckAndReplayStillDecrypts() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope e = p.send("demo-message-inbox", "inbox"); byte[] before = p.bs.load();
        p.bs.fail = true; rejects(() -> p.b.receive(e)); assertArrayEquals(before, p.bs.load());
        p.bs.fail = false; assertFalse(p.b.receive(e).duplicate); assertTrue(new SignalClient(p.bs).receive(e).duplicate); assertEquals(1, p.b.inbox().size());
    }
    @Test public void modifiedCiphertextRejectedWithoutConsumingPrekeysOrRatchet() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope e = p.send("demo-message-tamper", "tamper"); byte[] before = p.bs.load(), cipher = Base64.getDecoder().decode(e.bodyBase64); cipher[cipher.length - 1] ^= 1;
        rejects(() -> p.b.receive(alter(e, e.roomId, e.messageId, e.senderAccountId, e.senderDeviceId, e.recipientAccountId, e.recipientDeviceId, e.type, Base64.getEncoder().encodeToString(cipher))));
        assertArrayEquals(before, p.bs.load()); assertEquals("tamper", p.b.receive(e).text);
    }
    @Test public void outerContextTamperFailsAndOriginalStillWorks() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope e = p.send("demo-message-context", "bound"); byte[] before = p.bs.load();
        rejects(() -> p.b.receive(alter(e, e.roomId, "demo-message-other", e.senderAccountId, e.senderDeviceId, e.recipientAccountId, e.recipientDeviceId, e.type, e.bodyBase64)));
        rejects(() -> p.b.receive(alter(e, "demo-room-other", e.messageId, e.senderAccountId, e.senderDeviceId, e.recipientAccountId, e.recipientDeviceId, e.type, e.bodyBase64)));
        rejects(() -> p.b.receive(alter(e, e.roomId, e.messageId, e.senderAccountId, "demo-device-other", e.recipientAccountId, e.recipientDeviceId, e.type, e.bodyBase64)));
        rejects(() -> p.b.receive(alter(e, e.roomId, e.messageId, e.senderAccountId, e.senderDeviceId, e.recipientAccountId, "demo-device-other", e.type, e.bodyBase64)));
        assertArrayEquals(before, p.bs.load()); assertEquals("bound", p.b.receive(e).text);
    }
    @Test public void originAccountDeviceAndClaimRemainBoundAfterRestart() throws Exception {
        Memory m = new Memory(); SignalClient c = new SignalClient(m); c.initialize(ORIGIN, A, AD, 1); String claim = c.claimId(ROOM, BD);
        assertEquals(claim, new SignalClient(m).claimId(ROOM, BD));
        rejects(() -> c.bindAccount(B)); rejects(() -> c.prepareIdentity(ORIGIN, BD, 1)); rejects(() -> c.prepareIdentity(ORIGIN.replace('a', 'b'), AD, 1));
    }
    @Test public void corruptedStateFailsClosedInsteadOfResettingIdentity() throws Exception {
        Memory m = new Memory(); SignalClient c = new SignalClient(m); c.initialize(ORIGIN, A, AD, 1); m.bytes[0] ^= 1; byte[] corrupted = m.load();
        rejects(() -> c.prepareIdentity(ORIGIN, AD, 1)); assertArrayEquals(corrupted, m.load());
    }
    @Test public void wireRoundTripAndUnsupportedTypesReject() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope e = p.send("demo-message-wire", "wire");
        SignalClient.Envelope decoded = SignalClient.Envelope.fromWire(e.roomId, e.messageId, e.senderAccountId, e.senderDeviceId, e.recipientAccountId, e.recipientDeviceId, e.wireBase64());
        assertEquals("wire", p.b.receive(decoded).text);
        rejects(() -> p.b.receive(alter(e, e.roomId, e.messageId, e.senderAccountId, e.senderDeviceId, e.recipientAccountId, e.recipientDeviceId, 99, e.bodyBase64)));
    }
    @Test public void deviceCredentialCommitAndRetryAreStable() throws Exception {
        Memory m = new Memory(); SignalClient c = new SignalClient(m); c.initialize(ORIGIN, A, AD, 1);
        byte[] before = m.load(); m.fail = true; rejects(c::prepareDeviceToken); assertArrayEquals(before, m.load());
        m.fail = false; String token = c.prepareDeviceToken(); assertTrue(token.matches("gd_dev_[A-Za-z0-9_-]{43}"));
        assertEquals(token, new SignalClient(m).prepareDeviceToken()); assertEquals(token, c.deviceToken());
    }
    @Test public void freshRefillKeysAndPendingBatchSurviveRestart() throws Exception {
        Memory m = new Memory(); SignalClient c = new SignalClient(m); c.initialize(ORIGIN, A, AD, 3);
        SignalClient.Replenishment r = c.prepareReplenishment(2), again = new SignalClient(m).prepareReplenishment(1);
        assertEquals(r.batchId, again.batchId); assertEquals(2, again.oneTimePrekeys.size()); assertEquals(4, r.oneTimePrekeys.get(0).keyId);
        assertEquals(r.kyberPrekeys.get(1).publicKeyBase64, again.kyberPrekeys.get(1).publicKeyBase64);
        rejects(() -> c.confirmReplenishment("demo-batch-wrong")); c.confirmReplenishment(r.batchId);
        SignalClient.Replenishment next = c.prepareReplenishment(1); assertEquals(6, next.oneTimePrekeys.get(0).keyId); assertNotEquals(r.batchId, next.batchId);
        assertNotEquals(c.publication().oneTimePrekeys.get(0).publicKeyBase64, next.oneTimePrekeys.get(0).publicKeyBase64);
    }
    @Test public void compareAndSaveRejectsCrossInstanceStaleRatchet() throws Exception {
        Pair p = new Pair(); final boolean[] race = {true};
        SignalClient slow = new SignalClient(new SignalClient.StateStorage() {
            public byte[] load() throws IOException {
                byte[] snapshot = p.as.load();
                if (race[0]) { race[0] = false; try { p.send("demo-message-winning", "winning"); } catch (Exception error) { throw new IOException(error); } }
                return snapshot;
            }
            public void save(byte[] state) throws IOException { p.as.save(state); }
        });
        rejects(() -> slow.stageText(ROOM, B, BD, "demo-message-losing", "losing"));
        assertEquals(1, p.a.pendingOutbox().size()); assertEquals("winning", p.b.receive(p.a.pendingOutbox().get(0)).text);
    }
    @Test public void duplicateWithChangedBytesIsRejected() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope e = p.send("demo-message-repeat", "repeat"); p.b.receive(e);
        byte[] b = Base64.getDecoder().decode(e.bodyBase64); b[b.length - 1] ^= 1;
        rejects(() -> p.b.receive(alter(e, e.roomId, e.messageId, e.senderAccountId, e.senderDeviceId, e.recipientAccountId, e.recipientDeviceId, e.type, Base64.getEncoder().encodeToString(b))));
        assertEquals(1, p.b.inbox().size()); assertTrue(p.b.receive(e).duplicate);
    }

    @Test public void outOfOrderPrekeyMessagesAndHistoryTimeSurviveRestart() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope first = p.send("demo-message-ordered-first", "first");
        SignalClient.Envelope second = p.send("demo-message-ordered-second", "second");
        assertEquals("second", p.b.receive(second).text); assertEquals("first", p.b.receive(first).text);
        long created = p.a.sentMessages().get(0).createdAtMillis; assertTrue(created > 0);
        p.a.markRelayAccepted(p.a.sentMessages().get(0).envelope.messageId);
        assertEquals(created, new SignalClient(p.as).sentMessages().get(0).createdAtMillis);
        long received = p.b.inbox().get(0).createdAtMillis;
        assertEquals(received, new SignalClient(p.bs).receive(p.b.inbox().get(0).envelope).createdAtMillis);
    }

    @Test public void pendingRefillReadDoesNotGenerateOrAdvanceKeys() throws Exception {
        Memory m = new Memory(); SignalClient c = new SignalClient(m); c.initialize(ORIGIN, A, AD, 1);
        byte[] before = m.load(); assertNull(c.pendingReplenishment()); assertArrayEquals(before, m.load());
        SignalClient.Replenishment batch = c.prepareReplenishment(2); before = m.load();
        SignalClient.Replenishment pending = new SignalClient(m).pendingReplenishment();
        assertEquals(batch.batchId, pending.batchId); assertEquals(batch.oneTimePrekeys.get(1).publicKeyBase64, pending.oneTimePrekeys.get(1).publicKeyBase64);
        assertArrayEquals(before, m.load()); c.confirmReplenishment(batch.batchId); before = m.load();
        assertNull(c.pendingReplenishment()); assertArrayEquals(before, m.load());
    }
    @Test public void newIdentityExcludesHistoricalProtocolIdsAndExistingIdentityNeverChanges() throws Exception {
        java.util.Set<Integer> excluded = new java.util.HashSet<>(); for (int id = 1; id <= 127; id++) if (id != 73) excluded.add(id);
        Memory m = new Memory(); SignalClient c = new SignalClient(m); c.prepareIdentity(ORIGIN, AD, 1, excluded);
        assertEquals(73, c.protocolDeviceId()); String identity = c.identityKeyBase64(); byte[] before = m.load();
        excluded.add(73); new SignalClient(m).prepareIdentity(ORIGIN, AD, 1, excluded);
        assertEquals(73, c.protocolDeviceId()); assertEquals(identity, c.identityKeyBase64()); assertArrayEquals(before, m.load());
        Memory empty = new Memory(); SignalClient fresh = new SignalClient(empty);
        rejects(() -> fresh.prepareIdentity(ORIGIN, AD, 1, excluded)); assertNull(empty.bytes);
        rejects(() -> fresh.prepareIdentity(ORIGIN, AD, 1, java.util.Collections.singleton(0))); assertNull(empty.bytes);
        rejects(() -> fresh.prepareIdentity(ORIGIN, AD, 1, java.util.Collections.singleton(128))); assertNull(empty.bytes);
    }
    static SignalClient recoveredBob(Pair p) throws Exception {
        SignalClient recovered = new SignalClient(new Memory());
        recovered.prepareIdentity(ORIGIN, "demo-device-bob-recovered", 3, java.util.Collections.singleton(p.b.protocolDeviceId()));
        recovered.bindAccount(B); pin(recovered, p.a, A, AD); return recovered;
    }
    @Test public void explicitPeerRotationRequiresExactOldPinAndPreservesHistoryAndOldCiphertext() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope old = p.send("demo-message-before-rotation", "old message"); p.b.receive(old);
        p.a.receive(p.b.stageText(ROOM, A, AD, "demo-message-old-reply", "old reply"));
        p.a.acceptPeer("demo-room-older", B, BD, p.b.protocolDeviceId(), p.b.identityKeyBase64());
        SignalClient recovered = recoveredBob(p); String newDevice = recovered.deviceId(), oldIdentity = p.b.identityKeyBase64();
        byte[] before = p.as.load();
        rejects(() -> p.a.replacePeerIdentity(ROOM, B, newDevice, recovered.protocolDeviceId(), recovered.identityKeyBase64(), recovered.identityKeyBase64()));
        assertArrayEquals(before, p.as.load()); p.a.validateOutboundEnvelope(old);
        p.a.replacePeerIdentity(ROOM, B, newDevice, recovered.protocolDeviceId(), recovered.identityKeyBase64(), oldIdentity);
        assertFalse(p.a.hasSession(B, BD)); assertFalse(p.a.hasSession(B, newDevice));
        assertEquals(recovered.identityKeyBase64(), p.a.pinnedIdentityKeyBase64(B));
        assertEquals(1, p.a.inbox().size()); assertEquals("old reply", p.a.inbox().get(0).text);
        assertEquals(old.wireBase64(), p.a.sentMessages().get(0).envelope.wireBase64());
        assertTrue(p.a.sentMessages().get(0).retired); assertEquals(0, p.a.pendingOutbox().size());
        rejects(() -> p.a.validateOutboundEnvelope(old));
        rejects(() -> p.a.stageText(ROOM, B, BD, old.messageId, "old message"));
        rejects(() -> p.a.stageText("demo-room-older", B, newDevice, "demo-message-other-room", "must rebind room"));
        p.a.acceptBundle(ROOM, B, newDevice, recovered.publication().bundle(0));
        SignalClient.Envelope fresh = p.a.stageText(ROOM, B, newDevice, "demo-message-after-rotation", "new verified device");
        p.a.validateOutboundEnvelope(fresh); assertEquals("new verified device", recovered.receive(fresh).text);
        assertEquals("new reply", p.a.receive(recovered.stageText(ROOM, A, AD, "demo-message-new-reply", "new reply")).text);
        assertEquals(2, new SignalClient(p.as).inbox().size());
    }
    @Test public void failedRotationCommitDoesNotRetireOldOutboxOrReplacePin() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope old = p.send("demo-message-retained", "retained"); SignalClient recovered = recoveredBob(p);
        byte[] before = p.as.load(); p.as.fail = true;
        rejects(() -> p.a.replacePeerIdentity(ROOM, B, recovered.deviceId(), recovered.protocolDeviceId(), recovered.identityKeyBase64(), p.b.identityKeyBase64()));
        assertArrayEquals(before, p.as.load()); p.as.fail = false;
        assertTrue(p.a.hasSession(B, BD)); assertEquals(p.b.identityKeyBase64(), p.a.pinnedIdentityKeyBase64(B)); p.a.validateOutboundEnvelope(old);
        assertEquals("retained", p.b.receive(old).text);
    }
    @Test public void peerRotationNeverChangesUnrelatedPeerSession() throws Exception {
        Pair p = new Pair(); SignalClient other = new SignalClient(new Memory()); String c = "demo-account-charlie", cd = "demo-device-charlie", room = "demo-room-charlie";
        other.initialize(ORIGIN, c, cd, 1);
        other.acceptPeer(room, A, AD, p.a.protocolDeviceId(), p.a.identityKeyBase64());
        p.a.acceptPeer(room, c, cd, other.protocolDeviceId(), other.identityKeyBase64()); p.a.acceptBundle(room, c, cd, other.publication().bundle(0));
        SignalClient recovered = recoveredBob(p);
        p.a.replacePeerIdentity(ROOM, B, recovered.deviceId(), recovered.protocolDeviceId(), recovered.identityKeyBase64(), p.b.identityKeyBase64());
        assertTrue(p.a.hasSession(c, cd)); assertEquals(other.identityKeyBase64(), p.a.pinnedIdentityKeyBase64(c));
        assertEquals("other peer", other.receive(p.a.stageText(room, c, cd, "demo-message-unrelated", "other peer")).text);
    }
    @Test public void unchangedDeviceIdRotationStillRetiresOldCiphertext() throws Exception {
        Pair p = new Pair(); SignalClient.Envelope old = p.send("demo-message-old-device-id", "old ciphertext");
        SignalClient replacement = new SignalClient(new Memory()); replacement.initialize(ORIGIN, B, BD, 1);
        p.a.replacePeerIdentity(ROOM, B, BD, replacement.protocolDeviceId(), replacement.identityKeyBase64(), p.b.identityKeyBase64());
        rejects(() -> p.a.validateOutboundEnvelope(old));
        rejects(() -> p.a.stageText(ROOM, B, BD, old.messageId, "old ciphertext"));
        assertEquals(old.wireBase64(), p.a.sentMessages().get(0).envelope.wireBase64());
        assertTrue(p.a.sentMessages().get(0).retired); assertEquals(0, p.a.pendingOutbox().size());
    }

    @Test public void koreanEmojiAndMultilineRoundTripWithoutNormalization() throws Exception {
        Pair p = new Pair(); String text = "  안녕하세요 👩‍💻🙂\n둘째 줄\r\n가 e\u0301 가 café\t끝  ";
        SignalClient.Envelope e = p.send("demo-message-unicode-exact", text);
        assertEquals(text, p.b.receive(e).text); assertEquals(text, new SignalClient(p.bs).inbox().get(0).text);
        assertEquals(e.wireBase64(), new SignalClient(p.as).stageText(ROOM, B, BD, e.messageId, text).wireBase64());
        assertEquals(text, p.a.sentMessages().get(0).text);
    }
    @Test public void exactUtf8ByteBoundariesAcceptAsciiKoreanAndEmoji() throws Exception {
        String[] accepted = {"a".repeat(4096), "가".repeat(1365) + "a", "😀".repeat(1024)};
        Pair p = new Pair(); int n = 0;
        for (String text : accepted) {
            assertEquals(4096, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            SignalClient.checkText(text); assertEquals(text, p.b.receive(p.send("demo-message-limit-" + n++, text)).text);
        }
    }
    @Test public void overUtf8ByteBudgetNeverStagesCiphertext() throws Exception {
        String[] rejected = {"a".repeat(4097), "가".repeat(1365) + "ab", "😀".repeat(1024) + "a"};
        Pair p = new Pair(); byte[] before = p.as.load();
        for (String text : rejected) {
            try { SignalClient.checkText(text); fail("expected text_size"); }
            catch (IllegalArgumentException expected) { assertEquals("text_size", expected.getMessage()); }
            rejects(() -> p.send("demo-message-over-limit", text)); assertArrayEquals(before, p.as.load());
        }
        assertTrue(p.a.pendingOutbox().isEmpty());
    }
    @Test public void malformedSurrogatesRejectedBeforeDurableStateOrRetryChanges() throws Exception {
        String[] malformed = {"x\ud800", "\udc00x", "x\ud800y", "\ud800\ud800", "\udc00\ud800", "😀\udc00"};
        Pair p = new Pair(); SignalClient.Envelope original = p.send("demo-message-safe-retry", "safe?"); byte[] before = p.as.load();
        for (String text : malformed) {
            try { SignalClient.checkText(text); fail("expected text_encoding"); }
            catch (IllegalArgumentException expected) { assertEquals("text_encoding", expected.getMessage()); }
            rejects(() -> p.send("demo-message-invalid-unicode", text)); assertArrayEquals(before, p.as.load());
        }
        rejects(() -> p.send(original.messageId, "safe\ud800")); assertArrayEquals(before, p.as.load());
        assertEquals(original.wireBase64(), p.send(original.messageId, "safe?").wireBase64());
        assertEquals("safe?", p.b.receive(original).text);
    }


    @Test public void localArchiveMissingStateNeverCreatesIdentityOrWrites() throws Exception {
        Memory memory = new Memory(); memory.fail = true;
        SignalClient client = new SignalClient(memory);
        SignalClient.LocalRoomPage rooms = client.listLocalRooms(ORIGIN, A, "", "", 40);
        assertTrue(rooms.rooms.isEmpty()); assertEquals("", rooms.nextAfterRoomId);
        rejects(() -> client.openLocalRoom(ORIGIN, A, ROOM, B, "", 200));
        assertNull(memory.load()); assertEquals(0, memory.saveCalls);
        rejects(client::identityKeyBase64); assertEquals(0, memory.saveCalls);
    }

    @Test public void localArchiveRejectsWrongContextLimitsAndMalformedCursorsWithoutWrites() throws Exception {
        Pair p = new Pair(); p.send("demo-message-archive-bound", "bound archive");
        byte[] before = p.as.load(); int writes = p.as.saveCalls; p.as.fail = true;
        String otherOrigin = "http://" + "b".repeat(56) + ".onion";
        rejects(() -> p.a.listLocalRooms(otherOrigin, A, "", "", 40));
        rejects(() -> p.a.listLocalRooms(ORIGIN, A, null, "", 40));
        rejects(() -> p.a.listLocalRooms(ORIGIN, A, B, null, 40));
        rejects(() -> p.a.openLocalRoom(ORIGIN, A, ROOM, B, null, 200));
        rejects(() -> p.a.listLocalRooms(ORIGIN, B, "", "", 40));
        rejects(() -> p.a.openLocalRoom(otherOrigin, A, ROOM, B, "", 200));
        rejects(() -> p.a.openLocalRoom(ORIGIN, B, ROOM, B, "", 200));
        rejects(() -> p.a.openLocalRoom(ORIGIN, A, ROOM, "demo-account-charlie", "", 200));
        rejects(() -> p.a.openLocalRoom(ORIGIN, A, "demo-room-unknown", B, "", 200));
        for (int limit : new int[]{-1, 0, 41, Integer.MAX_VALUE})
            rejects(() -> p.a.listLocalRooms(ORIGIN, A, "", "", limit));
        for (int limit : new int[]{-1, 0, 201, Integer.MAX_VALUE})
            rejects(() -> p.a.openLocalRoom(ORIGIN, A, ROOM, B, "", limit));
        for (String cursor : new String[]{"invalid cursor", "../demo-room-pair", "demo-" + "x".repeat(81)})
            rejects(() -> p.a.listLocalRooms(ORIGIN, A, "", cursor, 40));
        for (String cursor : new String[]{"not-a-cursor", "!", "0", "x".repeat(4096)})
            rejects(() -> p.a.openLocalRoom(ORIGIN, A, ROOM, B, cursor, 200));
        assertEquals(1, p.a.listLocalRooms(ORIGIN, A, B, "", 1).rooms.size());
        assertEquals("bound archive", p.a.openLocalRoom(ORIGIN, A, ROOM, B, "", 1).messages.get(0).text);
        assertArrayEquals(before, p.as.load()); assertEquals(writes, p.as.saveCalls);
    }

    @Test public void localRoomPaginationIsBoundedVerifiedAndPeerFiltered() throws Exception {
        Pair p = new Pair(); List<String> bobRooms = new ArrayList<>(); bobRooms.add(ROOM);
        for (int i = 0; i < 42; i++) {
            String room = String.format(java.util.Locale.ROOT, "demo-room-archive-%03d", i);
            p.a.acceptPeer(room, B, BD, p.b.protocolDeviceId(), p.b.identityKeyBase64()); bobRooms.add(room);
        }
        String charlie = "demo-account-charlie", charlieDevice = "demo-device-charlie";
        SignalClient c = new SignalClient(new Memory()); c.initialize(ORIGIN, charlie, charlieDevice, 1);
        for (String room : new String[]{"demo-room-charlie-a", "demo-room-charlie-b"})
            p.a.acceptPeer(room, charlie, charlieDevice, c.protocolDeviceId(), c.identityKeyBase64());
        bobRooms.sort(String::compareTo); byte[] before = p.as.load(); int writes = p.as.saveCalls; p.as.fail = true;
        SignalClient restarted = new SignalClient(p.as);
        SignalClient.LocalRoomPage first = restarted.listLocalRooms(ORIGIN, A, B, "", 40);
        assertEquals(40, first.rooms.size()); assertEquals(bobRooms.subList(0, 40), localRoomIds(first));
        assertEquals(first.rooms.get(39).roomId, first.nextAfterRoomId);
        SignalClient.LocalRoomPage second = restarted.listLocalRooms(ORIGIN, A, B, first.nextAfterRoomId, 40);
        assertEquals(bobRooms.subList(40, 43), localRoomIds(second)); assertEquals("", second.nextAfterRoomId);
        for (SignalClient.LocalRoom room : first.rooms) {
            assertEquals(B, room.peerAccountId); assertEquals(0, room.messageCount); assertEquals(0L, room.lastMessageAtMillis);
        }
        SignalClient.LocalRoomPage allFirst = restarted.listLocalRooms(ORIGIN, A, "", "", 40);
        SignalClient.LocalRoomPage allSecond = restarted.listLocalRooms(ORIGIN, A, "", allFirst.nextAfterRoomId, 40);
        Set<String> allIds = new HashSet<>(localRoomIds(allFirst)); allIds.addAll(localRoomIds(allSecond));
        assertEquals(40, allFirst.rooms.size()); assertEquals(5, allSecond.rooms.size()); assertEquals(45, allIds.size());
        assertEquals("", allSecond.nextAfterRoomId);
        assertEquals(2, restarted.listLocalRooms(ORIGIN, A, charlie, "", 40).rooms.size());
        assertTrue(restarted.listLocalRooms(ORIGIN, A, "demo-account-unknown", "", 40).rooms.isEmpty());
        assertFalse(restarted.hasSession(charlie, charlieDevice));
        try { first.rooms.clear(); fail("room pages must be immutable"); } catch (UnsupportedOperationException expected) { }
        assertArrayEquals(before, p.as.load()); assertEquals(writes, p.as.saveCalls);
    }

    @Test public void localHistoryPagesRetainExactMessagesAndCiphertextWithWritesDisabled() throws Exception {
        Pair p = new Pair();
        String exact = "  안녕하세요 👩‍💻🙂\n둘째 줄\r\n가 e\u0301 가 café\t끝  ";
        for (int i = 0; i < 8; i++) {
            String id = "demo-message-archive-" + (9 - i), text = exact + i;
            if ((i & 1) == 0) {
                SignalClient.Envelope sent = p.send(id, text); p.b.receive(sent);
                if (i == 0) p.a.markRelayAccepted(id);
            } else p.a.receive(p.b.stageText(ROOM, A, AD, id, text));
        }
        String otherRoom = "demo-room-archive-other";
        p.a.acceptPeer(otherRoom, B, BD, p.b.protocolDeviceId(), p.b.identityKeyBase64());
        p.a.stageText(otherRoom, B, BD, "demo-message-outside-history", "different room");
        List<SignalClient.Incoming> expected = new ArrayList<>(p.a.inbox());
        for (SignalClient.Incoming message : p.a.sentMessages()) if (message.envelope.roomId.equals(ROOM)) expected.add(message);
        expected.sort(Comparator.comparingLong((SignalClient.Incoming value) -> value.createdAtMillis)
                .thenComparing(value -> value.envelope.messageId));
        byte[] before = p.as.load(); int writes = p.as.saveCalls; p.as.fail = true;
        SignalClient restarted = new SignalClient(p.as);
        SignalClient.LocalHistoryPage latest = restarted.openLocalRoom(ORIGIN, A, ROOM, B, "", 3);
        assertEquals(ROOM, latest.room.roomId); assertEquals(B, latest.room.peerAccountId);
        assertEquals(8, latest.room.messageCount); assertEquals(expected.get(7).createdAtMillis, latest.room.lastMessageAtMillis);
        assertLocalMessages(expected.subList(5, 8), latest.messages); assertFalse(latest.nextBeforeMessageCursor.isEmpty());
        SignalClient.LocalHistoryPage middle = restarted.openLocalRoom(ORIGIN, A, ROOM, B, latest.nextBeforeMessageCursor, 3);
        assertLocalMessages(expected.subList(2, 5), middle.messages); assertFalse(middle.nextBeforeMessageCursor.isEmpty());
        SignalClient.LocalHistoryPage oldest = restarted.openLocalRoom(ORIGIN, A, ROOM, B, middle.nextBeforeMessageCursor, 3);
        assertLocalMessages(expected.subList(0, 2), oldest.messages); assertEquals("", oldest.nextBeforeMessageCursor);
        assertLocalMessages(expected, restarted.openLocalRoom(ORIGIN, A, ROOM, B, "", 200).messages);
        assertLocalMessages(latest.messages, restarted.openLocalRoom(ORIGIN, A, ROOM, B, "", 3).messages);
        rejects(() -> restarted.openLocalRoom(ORIGIN, A, otherRoom, B, latest.nextBeforeMessageCursor, 3));
        try { latest.messages.clear(); fail("history pages must be immutable"); } catch (UnsupportedOperationException expectedFailure) { }
        assertArrayEquals(before, p.as.load()); assertEquals(writes, p.as.saveCalls);
    }

    @Test public void localArchiveRetainsHistoricalRoomsAfterIdentityRotationRemovesBindings() throws Exception {
        Pair p = new Pair(); String archivedRoom = "demo-room-before-rotation", emptyRoom = "demo-room-empty-before-rotation";
        p.a.acceptPeer(archivedRoom, B, BD, p.b.protocolDeviceId(), p.b.identityKeyBase64());
        p.b.acceptPeer(archivedRoom, A, AD, p.a.protocolDeviceId(), p.a.identityKeyBase64());
        p.a.acceptPeer(emptyRoom, B, BD, p.b.protocolDeviceId(), p.b.identityKeyBase64());
        SignalClient.Envelope old = p.a.stageText(archivedRoom, B, BD, "demo-message-archived-send", "old sent 안녕 👋");
        p.b.receive(old); p.a.receive(p.b.stageText(archivedRoom, A, AD, "demo-message-archived-reply", "old received\nreply"));
        SignalClient recovered = recoveredBob(p);
        p.a.replacePeerIdentity(ROOM, B, recovered.deviceId(), recovered.protocolDeviceId(), recovered.identityKeyBase64(), p.b.identityKeyBase64());
        byte[] before = p.as.load(); int writes = p.as.saveCalls; p.as.fail = true;
        SignalClient restarted = new SignalClient(p.as);
        SignalClient.LocalRoomPage rooms = restarted.listLocalRooms(ORIGIN, A, B, "", 40);
        assertEquals(Arrays.asList(archivedRoom, ROOM), localRoomIds(rooms));
        SignalClient.LocalHistoryPage archive = restarted.openLocalRoom(ORIGIN, A, archivedRoom, B, "", 200);
        assertEquals(2, archive.room.messageCount); assertEquals(2, archive.messages.size());
        SignalClient.Incoming outgoing = null, incoming = null;
        for (SignalClient.Incoming message : archive.messages)
            if (message.envelope.senderAccountId.equals(A)) outgoing = message; else incoming = message;
        assertNotNull(outgoing); assertNotNull(incoming); assertTrue(outgoing.retired); assertFalse(incoming.retired);
        assertEquals("old sent 안녕 👋", outgoing.text); assertEquals(old.wireBase64(), outgoing.envelope.wireBase64());
        assertEquals("old received\nreply", incoming.text);
        assertTrue(restarted.openLocalRoom(ORIGIN, A, ROOM, B, "", 200).messages.isEmpty());
        rejects(() -> restarted.openLocalRoom(ORIGIN, A, emptyRoom, B, "", 200));
        assertFalse(restarted.hasSession(B, BD)); assertFalse(restarted.hasSession(B, recovered.deviceId()));
        assertArrayEquals(before, p.as.load()); assertEquals(writes, p.as.saveCalls);
    }

    @Test public void localArchiveRejectsRoomReboundToDifferentPeerAfterHistoricalMessages() throws Exception {
        Pair p = new Pair(); String archivedRoom = "demo-room-conflicting-history";
        p.a.acceptPeer(archivedRoom, B, BD, p.b.protocolDeviceId(), p.b.identityKeyBase64());
        p.a.stageText(archivedRoom, B, BD, "demo-message-before-rebind", "Bob history");
        SignalClient recovered = recoveredBob(p);
        p.a.replacePeerIdentity(ROOM, B, recovered.deviceId(), recovered.protocolDeviceId(), recovered.identityKeyBase64(), p.b.identityKeyBase64());
        String charlie = "demo-account-charlie", charlieDevice = "demo-device-charlie";
        SignalClient c = new SignalClient(new Memory()); c.initialize(ORIGIN, charlie, charlieDevice, 1);
        p.a.acceptPeer(archivedRoom, charlie, charlieDevice, c.protocolDeviceId(), c.identityKeyBase64());
        byte[] before = p.as.load(); int writes = p.as.saveCalls; p.as.fail = true;
        rejects(() -> p.a.listLocalRooms(ORIGIN, A, "", "", 40));
        rejects(() -> p.a.openLocalRoom(ORIGIN, A, archivedRoom, B, "", 200));
        rejects(() -> p.a.openLocalRoom(ORIGIN, A, archivedRoom, charlie, "", 200));
        assertArrayEquals(before, p.as.load()); assertEquals(writes, p.as.saveCalls);
    }

    static List<String> localRoomIds(SignalClient.LocalRoomPage page) {
        List<String> ids = new ArrayList<>(); for (SignalClient.LocalRoom room : page.rooms) ids.add(room.roomId); return ids;
    }
    static void assertLocalMessages(List<SignalClient.Incoming> expected, List<SignalClient.Incoming> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            SignalClient.Incoming left = expected.get(i), right = actual.get(i);
            assertEquals(left.text, right.text); assertEquals(left.createdAtMillis, right.createdAtMillis);
            assertEquals(left.relayAccepted, right.relayAccepted); assertEquals(left.retired, right.retired); assertFalse(right.duplicate);
            assertEquals(left.envelope.roomId, right.envelope.roomId); assertEquals(left.envelope.messageId, right.envelope.messageId);
            assertEquals(left.envelope.senderAccountId, right.envelope.senderAccountId); assertEquals(left.envelope.senderDeviceId, right.envelope.senderDeviceId);
            assertEquals(left.envelope.recipientAccountId, right.envelope.recipientAccountId); assertEquals(left.envelope.recipientDeviceId, right.envelope.recipientDeviceId);
            assertEquals(left.envelope.type, right.envelope.type); assertEquals(left.envelope.bodyBase64, right.envelope.bodyBase64);
            assertEquals(left.envelope.wireBase64(), right.envelope.wireBase64());
        }
    }

}
