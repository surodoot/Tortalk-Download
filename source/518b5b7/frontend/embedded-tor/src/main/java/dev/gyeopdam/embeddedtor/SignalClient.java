package dev.gyeopdam.embeddedtor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.*;
import org.signal.libsignal.protocol.kem.*;
import org.signal.libsignal.protocol.message.*;
import org.signal.libsignal.protocol.state.*;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.util.KeyHelper;

/**
 * Single-device, one-to-one Signal/PQ client. No network or plaintext disk access.
 * StateStorage must encrypt, atomically replace and fsync the whole state, and must
 * have exactly one SignalClient owner. A failed commit NEVER releases ciphertext,
 * plaintext, or permission to ACK. Reopening loads the last committed ratchet.
 * Peer identity pins must be explicitly confirmed from a contact/QR before session setup.
 */
public final class SignalClient {
    public interface StateStorage {
        byte[] load() throws IOException;
        void save(byte[] state) throws IOException;
        /** Android must override using the encrypted store's locked write validator. */
        default void compareAndSave(byte[] expected, byte[] state) throws IOException {
            synchronized (this) {
                if (!Arrays.equals(expected, load())) throw new IOException("stale_signal_state");
                save(state);
            }
        }
    }
    public static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_STATE = 16 * 1024 * 1024;
    private static final int MAX_RECORDS = 12000;
    private final StateStorage storage;
    public SignalClient(StateStorage storage) { this.storage = Objects.requireNonNull(storage); }

    public static final class PublicKey {
        public final int keyId;
        public final String publicKeyBase64, signatureBase64;
        public PublicKey(int keyId, String publicKeyBase64, String signatureBase64) {
            this.keyId = keyId; this.publicKeyBase64 = publicKeyBase64; this.signatureBase64 = signatureBase64;
        }
    }
    public static final class Publication {
        public final int registrationId, protocolDeviceId;
        public final String identityKeyBase64;
        public final PublicKey signedPrekey;
        public final List<PublicKey> oneTimePrekeys, kyberPrekeys;
        Publication(int registration, int device, String identity, PublicKey signed, List<PublicKey> ec, List<PublicKey> kyber) {
            registrationId = registration; protocolDeviceId = device; identityKeyBase64 = identity;
            signedPrekey = signed; oneTimePrekeys = Collections.unmodifiableList(ec); kyberPrekeys = Collections.unmodifiableList(kyber);
        }
        public Bundle bundle(int index) {
            return new Bundle(registrationId, protocolDeviceId, identityKeyBase64, signedPrekey, oneTimePrekeys.get(index), kyberPrekeys.get(index));
        }
    }
    public static final class Replenishment {
        public final String batchId;
        public final List<PublicKey> oneTimePrekeys, kyberPrekeys;
        Replenishment(String id, List<PublicKey> ec, List<PublicKey> kyber) {
            batchId = id; oneTimePrekeys = Collections.unmodifiableList(ec); kyberPrekeys = Collections.unmodifiableList(kyber);
        }
    }
    public static final class Bundle {
        public final int registrationId, protocolDeviceId;
        public final String identityKeyBase64;
        public final PublicKey signedPrekey, oneTimePrekey, kyberPrekey;
        public Bundle(int registration, int device, String identity, PublicKey signed, PublicKey ec, PublicKey kyber) {
            registrationId = registration; protocolDeviceId = device; identityKeyBase64 = identity;
            signedPrekey = signed; oneTimePrekey = ec; kyberPrekey = kyber;
        }
    }
    public static final class Envelope {
        public final String roomId, messageId, senderAccountId, senderDeviceId, recipientAccountId, recipientDeviceId;
        public final int type;
        public final String bodyBase64;
        public Envelope(String room, String message, String senderAccount, String senderDevice,
                        String recipientAccount, String recipientDevice, int type, String body) {
            roomId = room; messageId = message; senderAccountId = senderAccount; senderDeviceId = senderDevice;
            recipientAccountId = recipientAccount; recipientDeviceId = recipientDevice; this.type = type; bodyBase64 = body;
        }
        /** Server's signal-pq-v1 ciphertext is one type byte followed by libsignal bytes. */
        public String wireBase64() {
            byte[] body = b64(bodyBase64); byte[] wire = new byte[body.length + 1]; wire[0] = (byte) type;
            System.arraycopy(body, 0, wire, 1, body.length); return b64(wire);
        }
        public static Envelope fromWire(String room, String message, String senderAccount, String senderDevice,
                                        String recipientAccount, String recipientDevice, String wireBase64) {
            byte[] wire = b64(wireBase64);
            if (wire.length < 2 || wire.length > 96 * 1024) throw invalid("invalid_wire");
            return new Envelope(room, message, senderAccount, senderDevice, recipientAccount, recipientDevice,
                    wire[0] & 255, b64(Arrays.copyOfRange(wire, 1, wire.length)));
        }
    }
    public static final class Incoming {
        public final Envelope envelope;
        public final String text;
        public final boolean duplicate, relayAccepted, retired;
        public final long createdAtMillis;
        Incoming(Envelope envelope, String text, boolean duplicate, boolean accepted, long createdAt) {
            this(envelope, text, duplicate, accepted, createdAt, false);
        }
        Incoming(Envelope envelope, String text, boolean duplicate, boolean accepted, long createdAt, boolean retired) {
            this.envelope = envelope; this.text = text; this.duplicate = duplicate; relayAccepted = accepted; createdAtMillis = createdAt; this.retired = retired;
        }
    }
    /** Public archive metadata only. These values never authorize a send or a peer pin. */
    public static final class LocalRoom {
        public final String roomId, peerAccountId;
        public final int messageCount;
        public final long lastMessageAtMillis;
        LocalRoom(String room, String peer, int count, long last) {
            roomId = room; peerAccountId = peer; messageCount = count; lastMessageAtMillis = last;
        }
    }
    public static final class LocalRoomPage {
        public final List<LocalRoom> rooms;
        public final String nextAfterRoomId;
        LocalRoomPage(List<LocalRoom> values, String next) {
            rooms = Collections.unmodifiableList(new ArrayList<>(values)); nextAfterRoomId = next;
        }
    }
    public static final class LocalHistoryPage {
        public final LocalRoom room;
        public final List<Incoming> messages;
        public final String nextBeforeMessageCursor;
        LocalHistoryPage(LocalRoom value, List<Incoming> history, String next) {
            room = value; messages = Collections.unmodifiableList(new ArrayList<>(history)); nextBeforeMessageCursor = next;
        }
    }

    /** Generate independently before binding the identity public key to the server device. */
    public synchronized void prepareIdentity(String origin, String deviceId, int prekeyCount) throws Exception {
        prepareIdentity(origin, deviceId, prekeyCount, Collections.emptySet());
    }
    /** Historical protocol IDs are excluded only when generating this profile for the first time. */
    public synchronized void prepareIdentity(String origin, String deviceId, int prekeyCount,
                                              Set<Integer> excludedProtocolIds) throws Exception {
        origin(origin); id(deviceId);
        if (prekeyCount < 1 || prekeyCount > 20) throw invalid("prekey_count");
        State s = load();
        if (s.get("meta|origin") != null) {
            require(s.text("meta|origin").equals(origin) && s.text("meta|device").equals(deviceId), "local_binding_changed");
            return;
        }
        require(excludedProtocolIds != null, "missing_protocol_exclusions");
        Set<Integer> excluded = new HashSet<>(excludedProtocolIds);
        for (Integer protocol : excluded) require(protocol != null && protocol >= 1 && protocol <= 127, "invalid_protocol_exclusion");
        List<Integer> available = new ArrayList<>();
        for (int protocol = 1; protocol <= 127; protocol++) if (!excluded.contains(protocol)) available.add(protocol);
        require(!available.isEmpty(), "protocol_ids_exhausted");
        int protocolId = available.get(new java.security.SecureRandom().nextInt(available.size()));
        s.text("meta|origin", origin); s.text("meta|device", deviceId);
        s.text("meta|protocol", Integer.toString(protocolId));
        IdentityKeyPair identity = IdentityKeyPair.generate();
        s.put("local|identity", identity.serialize()); s.text("local|registration", Integer.toString(KeyHelper.generateRegistrationId(false)));
        ECKeyPair signed = ECKeyPair.generate();
        byte[] signature = identity.getPrivateKey().calculateSignature(signed.getPublicKey().serialize());
        s.storeSignedPreKey(1, new SignedPreKeyRecord(1, System.currentTimeMillis(), signed, signature));
        List<PublicKey> ec = new ArrayList<>(), kyber = new ArrayList<>();
        for (int i = 1; i <= prekeyCount; i++) {
            ECKeyPair e = ECKeyPair.generate(); KEMKeyPair k = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            byte[] sig = identity.getPrivateKey().calculateSignature(k.getPublicKey().serialize());
            s.storePreKey(i, new PreKeyRecord(i, e));
            s.storeKyberPreKey(i, new KyberPreKeyRecord(i, System.currentTimeMillis(), k, sig));
            ec.add(new PublicKey(i, b64(e.getPublicKey().serialize()), null));
            kyber.add(new PublicKey(i, b64(k.getPublicKey().serialize()), b64(sig)));
        }
        Publication p = new Publication(s.getLocalRegistrationId(), Integer.parseInt(s.text("meta|protocol")), b64(identity.getPublicKey().serialize()),
                new PublicKey(1, b64(signed.getPublicKey().serialize()), b64(signature)), ec, kyber);
        s.put("local|publication", encodePublication(p)); s.text("local|highwater", Integer.toString(prekeyCount)); commit(s);
    }
    public synchronized void bindAccount(String accountId) throws Exception {
        id(accountId); State s = initialized(); String old = s.text("meta|account");
        require(old.isEmpty() || old.equals(accountId), "account_binding_changed"); s.text("meta|account", accountId); commit(s);
    }
    public synchronized void initialize(String origin, String accountId, String deviceId, int count) throws Exception {
        prepareIdentity(origin, deviceId, count); bindAccount(accountId);
    }
    public synchronized Publication publication() throws Exception { return decodePublication(initialized().get("local|publication")); }
    public synchronized String identityKeyBase64() throws Exception { return publication().identityKeyBase64; }
    public synchronized String devicePublicKeyBase64() throws Exception {
        byte[] identity = b64(identityKeyBase64()); require(identity.length == 33 && identity[0] == 5, "identity_encoding");
        return b64(Arrays.copyOfRange(identity, 1, identity.length));
    }
    public synchronized String origin() throws Exception { return initialized().text("meta|origin"); }
    public synchronized String accountId() throws Exception { return bound().text("meta|account"); }
    public synchronized String deviceId() throws Exception { return initialized().text("meta|device"); }

    /** A device capability generated once locally and encrypted before server enrollment. */
    public synchronized String prepareDeviceToken() throws Exception {
        State s = bound(); String old = s.text("local|device-token"); if (!old.isEmpty()) return old;
        byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
        String token = "gd_dev_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Arrays.fill(random, (byte) 0); s.text("local|device-token", token); commit(s); return token;
    }
    public synchronized String deviceToken() throws Exception { return bound().text("local|device-token"); }
    /** Read an outstanding batch without generating keys or advancing the local key high-water mark. */
    public synchronized Replenishment pendingReplenishment() throws Exception {
        byte[] pending = bound().get("local|refill"); return pending == null ? null : decodeReplenishment(pending);
    }
    /** Call with server-confirmed available capacity; an outstanding retry never generates new keys. */
    public synchronized Replenishment prepareReplenishment(int count) throws Exception {
        require(count >= 1 && count <= 20, "prekey_count"); State s = bound();
        if (s.get("local|refill") != null) return decodeReplenishment(s.get("local|refill"));
        int high = Integer.parseInt(s.text("local|highwater")); require(high <= Integer.MAX_VALUE - count, "prekey_id_exhausted");
        List<PublicKey> ec = new ArrayList<>(), kyber = new ArrayList<>(); IdentityKeyPair identity = s.getIdentityKeyPair();
        for (int offset = 1; offset <= count; offset++) {
            int i = high + offset;
            ECKeyPair e = ECKeyPair.generate(); KEMKeyPair k = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            byte[] sig = identity.getPrivateKey().calculateSignature(k.getPublicKey().serialize());
            s.storePreKey(i, new PreKeyRecord(i, e)); s.storeKyberPreKey(i, new KyberPreKeyRecord(i, System.currentTimeMillis(), k, sig));
            ec.add(new PublicKey(i, b64(e.getPublicKey().serialize()), null)); kyber.add(new PublicKey(i, b64(k.getPublicKey().serialize()), b64(sig)));
        }
        Replenishment result = new Replenishment("demo-batch-" + UUID.randomUUID(), ec, kyber);
        s.text("local|highwater", Integer.toString(high + count)); s.put("local|refill", encodeReplenishment(result)); commit(s); return result;
    }
    public synchronized void confirmReplenishment(String batchId) throws Exception {
        id(batchId); State s = bound(); byte[] pending = s.get("local|refill"); require(pending != null, "no_pending_refill");
        require(decodeReplenishment(pending).batchId.equals(batchId), "refill_id_conflict"); s.remove("local|refill"); commit(s);
    }

    /** Persist before issuing HTTP; reuse exactly this id after network/process failure. */
    public synchronized String claimId(String roomId, String peerDeviceId) throws Exception {
        id(roomId); id(peerDeviceId); State s = bound(); String key = "claim|" + roomId + "|" + peerDeviceId;
        if (s.get(key) != null) return s.text(key);
        String claim = "demo-claim-" + UUID.randomUUID(); s.text(key, claim); commit(s); return claim;
    }
    public synchronized int protocolDeviceId() throws Exception { return Integer.parseInt(initialized().text("meta|protocol")); }
    /** Call ONLY after explicit UI confirmation of the contact identity/fingerprint or verified QR. */
    public synchronized void acceptPeer(String roomId, String peerAccountId, String peerDeviceId,
                                        int protocolDeviceId, String identityKeyBase64) throws Exception {
        id(roomId); id(peerAccountId); id(peerDeviceId); keyBytes(identityKeyBase64, 33, 5);
        require(protocolDeviceId >= 1 && protocolDeviceId <= 127, "protocol_device_id");
        State s = bound(); require(!peerAccountId.equals(s.text("meta|account")), "self_contact");
        bindPeer(s, roomId, peerAccountId, peerDeviceId);
        String oldProtocol = s.text("protocol|" + peerAccountId);
        require(oldProtocol.isEmpty() || oldProtocol.equals(Integer.toString(protocolDeviceId)), "peer_protocol_changed");
        s.text("protocol|" + peerAccountId, Integer.toString(protocolDeviceId));
        s.saveIdentity(address(s, peerAccountId), new IdentityKey(b64(identityKeyBase64))); commit(s);
    }
    /**
     * Call ONLY after explicit old/new fingerprint confirmation over a trusted channel.
     * Deletes this peer's old sessions and room bindings; history and ciphertext are retained.
     * Only the requested room is rebound. Old outbox bytes are retired, never re-encrypted.
     */
    public synchronized void replacePeerIdentity(String roomId, String peerAccountId, String newDeviceId,
                                                int newProtocolId, String newIdentityBase64,
                                                String expectedOldIdentityBase64) throws Exception {
        id(roomId); id(peerAccountId); id(newDeviceId);
        keyBytes(newIdentityBase64, 33, 5); keyBytes(expectedOldIdentityBase64, 33, 5);
        require(newProtocolId >= 1 && newProtocolId <= 127, "protocol_device_id");
        State s = bound(); require(!peerAccountId.equals(s.text("meta|account")), "self_contact");
        String oldDevice = s.text("peer|" + peerAccountId), oldProtocol = s.text("protocol|" + peerAccountId);
        require(!oldDevice.isEmpty() && !oldProtocol.isEmpty(), "identity_unpinned");
        IdentityKey oldIdentity = s.getIdentity(address(s, peerAccountId));
        IdentityKey expected = new IdentityKey(b64(expectedOldIdentityBase64));
        IdentityKey replacement = new IdentityKey(b64(newIdentityBase64));
        require(oldIdentity != null && oldIdentity.equals(expected), "old_identity_mismatch");
        require(!oldIdentity.equals(replacement) || !oldDevice.equals(newDeviceId)
                || !oldProtocol.equals(Integer.toString(newProtocolId)), "peer_binding_unchanged");
        s.deleteAllSessions(peerAccountId);
        Iterator<Map.Entry<String,byte[]>> entries = s.records.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String,byte[]> entry = entries.next(); String key = entry.getKey();
            if (key.startsWith("identity|" + peerAccountId + "|")
                    || key.startsWith("room|") && new String(entry.getValue(), StandardCharsets.UTF_8).startsWith(peerAccountId + "|")) entries.remove();
        }
        s.remove("peer|" + peerAccountId); s.remove("protocol|" + peerAccountId);
        for (byte[] bytes : s.all("out|")) {
            Stored message = decodeStored(bytes);
            if (message.envelope.recipientAccountId.equals(peerAccountId)) s.text("retired-out|" + message.envelope.messageId, "1");
        }
        bindPeer(s, roomId, peerAccountId, newDeviceId);
        s.text("protocol|" + peerAccountId, Integer.toString(newProtocolId));
        s.saveIdentity(address(s, peerAccountId), replacement); commit(s);
    }
    /** Refuse retired ciphertext before the transport can assign current peer key metadata to it. */
    public synchronized void validateOutboundEnvelope(Envelope envelope) throws Exception {
        validateEnvelope(envelope); State s = bound();
        require(envelope.senderAccountId.equals(s.text("meta|account")) && envelope.senderDeviceId.equals(s.text("meta|device")), "wrong_sender");
        requirePeer(s, envelope.roomId, envelope.recipientAccountId, envelope.recipientDeviceId);
        require(s.get("retired-out|" + envelope.messageId) == null, "peer_binding_retired");
        byte[] stored = s.get("out|" + envelope.messageId);
        require(stored != null && sameEnvelope(decodeStored(stored).envelope, envelope), "unstaged_ciphertext");
    }

    /** Caller must first validate server claim_id/room_id/account_id/device_id against the requested contact. */
    public synchronized void acceptBundle(String roomId, String peerAccountId, String peerDeviceId, Bundle bundle) throws Exception {
        id(roomId); id(peerAccountId); id(peerDeviceId); State s = bound();
        require(!peerAccountId.equals(s.text("meta|account")), "self_contact");
        validateBundle(bundle);
        requirePeer(s, roomId, peerAccountId, peerDeviceId);
        require(Integer.toString(bundle.protocolDeviceId).equals(s.text("protocol|" + peerAccountId)), "bundle_protocol_changed");
        SignalProtocolAddress peer = address(s, peerAccountId); IdentityKey identity = new IdentityKey(b64(bundle.identityKeyBase64));
        IdentityKey old = s.getIdentity(peer);
        require(old != null && old.equals(identity), "identity_changed");
        // Verify both signatures even when an already-active session must not be reset.
        require(identity.getPublicKey().verifySignature(b64(bundle.signedPrekey.publicKeyBase64), b64(bundle.signedPrekey.signatureBase64)), "signed_prekey_signature");
        require(identity.getPublicKey().verifySignature(b64(bundle.kyberPrekey.publicKeyBase64), b64(bundle.kyberPrekey.signatureBase64)), "kyber_prekey_signature");
        if (!s.containsSession(peer)) {
            PreKeyBundle keys = new PreKeyBundle(bundle.registrationId, bundle.protocolDeviceId,
                    bundle.oneTimePrekey.keyId, new ECPublicKey(b64(bundle.oneTimePrekey.publicKeyBase64)),
                    bundle.signedPrekey.keyId, new ECPublicKey(b64(bundle.signedPrekey.publicKeyBase64)), b64(bundle.signedPrekey.signatureBase64), identity,
                    bundle.kyberPrekey.keyId, new KEMPublicKey(b64(bundle.kyberPrekey.publicKeyBase64)), b64(bundle.kyberPrekey.signatureBase64));
            new SessionBuilder(s, peer, address(s, s.text("meta|account"))).process(keys);
        }
        commit(s);
    }
    public synchronized boolean hasSession(String peerAccountId, String peerDeviceId) throws Exception {
        id(peerAccountId); id(peerDeviceId); State s = bound();
        return peerDeviceId.equals(s.text("peer|" + peerAccountId)) && s.containsSession(address(s, peerAccountId));
    }
    public synchronized String pinnedIdentityKeyBase64(String peerAccountId) throws Exception {
        id(peerAccountId); State s = bound(); IdentityKey key = s.getIdentity(address(s, peerAccountId));
        return key == null ? null : b64(key.serialize());
    }
    public synchronized Envelope stageText(String roomId, String peerAccountId, String peerDeviceId, String messageId, String text) throws Exception {
        id(roomId); id(peerAccountId); id(peerDeviceId); id(messageId); checkText(text);
        State s = bound(); requirePeer(s, roomId, peerAccountId, peerDeviceId);
        Envelope context = new Envelope(roomId, messageId, s.text("meta|account"), s.text("meta|device"), peerAccountId, peerDeviceId, 0, "");
        require(s.get("retired-out|" + messageId) == null, "peer_binding_retired");
        byte[] old = s.get("out|" + messageId);
        if (old != null) {
            Stored m = decodeStored(old); require(sameContext(m.envelope, context) && m.text.equals(text), "outbox_id_conflict"); return m.envelope;
        }
        CiphertextMessage encrypted = cipher(s, peerAccountId).encrypt(frame(context, text));
        Envelope e = new Envelope(roomId, messageId, context.senderAccountId, context.senderDeviceId, peerAccountId, peerDeviceId, encrypted.getType(), b64(encrypted.serialize()));
        s.put("out|" + messageId, encodeStored(new Stored(e, text, false))); commit(s); return e;
    }
    /** A successfully returned result, including an identical replay, can now be ACKed. */
    public synchronized Incoming receive(Envelope envelope) throws Exception {
        validateEnvelope(envelope); State s = bound();
        require(envelope.recipientAccountId.equals(s.text("meta|account")) && envelope.recipientDeviceId.equals(s.text("meta|device")), "wrong_recipient");
        requirePeer(s, envelope.roomId, envelope.senderAccountId, envelope.senderDeviceId);
        byte[] old = s.get("in|" + envelope.messageId);
        if (old != null) {
            Stored m = decodeStored(old); require(sameEnvelope(m.envelope, envelope), "inbox_id_conflict"); return new Incoming(m.envelope, m.text, true, false, m.createdAtMillis);
        }
        SessionCipher c = cipher(s, envelope.senderAccountId); byte[] body = b64(envelope.bodyBase64);
        byte[] plaintext;
        if (envelope.type == CiphertextMessage.PREKEY_TYPE) plaintext = c.decrypt(new PreKeySignalMessage(body));
        else if (envelope.type == CiphertextMessage.WHISPER_TYPE) plaintext = c.decrypt(new SignalMessage(body));
        else throw invalid("unsupported_ciphertext_type");
        String text = parseFrame(plaintext, envelope);
        Stored received = new Stored(envelope, text, true);
        s.put("in|" + envelope.messageId, encodeStored(received)); commit(s);
        return new Incoming(envelope, text, false, false, received.createdAtMillis);
    }
    public synchronized List<Envelope> pendingOutbox() throws Exception {
        List<Envelope> result = new ArrayList<>(); State s = bound();
        for (byte[] b : s.all("out|")) { Stored m = decodeStored(b); if (!m.accepted && s.get("retired-out|" + m.envelope.messageId) == null) result.add(m.envelope); }
        return Collections.unmodifiableList(result);
    }
    /** Relay accepted != delivered/read. Retains exact ciphertext for idempotent retries. */
    public synchronized void markRelayAccepted(String messageId) throws Exception {
        id(messageId); State s = bound(); byte[] b = s.get("out|" + messageId); require(b != null, "unknown_outbox");
        Stored m = decodeStored(b); s.put("out|" + messageId, encodeStored(new Stored(m.envelope, m.text, true, m.createdAtMillis))); commit(s);
    }
    public synchronized List<Incoming> inbox() throws Exception {
        List<Incoming> result = new ArrayList<>(); for (byte[] b : bound().all("in|")) { Stored m = decodeStored(b); result.add(new Incoming(m.envelope, m.text, false, false, m.createdAtMillis)); }
        return Collections.unmodifiableList(result);
    }
    public synchronized List<Incoming> sentMessages() throws Exception {
        List<Incoming> result = new ArrayList<>(); State s = bound(); for (byte[] b : s.all("out|")) { Stored m = decodeStored(b); result.add(new Incoming(m.envelope, m.text, false, m.accepted, m.createdAtMillis, s.get("retired-out|" + m.envelope.messageId) != null)); }
        return Collections.unmodifiableList(result);
    }

    /** Read one encrypted snapshot. Never initializes identities, reconstructs sessions or commits state. */
    public synchronized LocalRoomPage listLocalRooms(String expectedOrigin, String expectedAccount,
                                                     String peerAccount, String afterRoomId, int limit) throws Exception {
        require(peerAccount != null, "invalid_identifier");
        if (!peerAccount.isEmpty()) { id(peerAccount); require(!peerAccount.equals(expectedAccount), "self_contact"); }
        require(afterRoomId != null && limit >= 1 && limit <= 40, "archive_page_limit");
        if (!afterRoomId.isEmpty()) id(afterRoomId);
        State s = archiveState(expectedOrigin, expectedAccount);
        List<LocalRoom> result = new ArrayList<>(); boolean more = false;
        for (LocalRoomIndex room : archiveIndex(s).values()) {
            if (!peerAccount.isEmpty() && !room.peer.equals(peerAccount) || room.room.compareTo(afterRoomId) <= 0) continue;
            if (result.size() == limit) { more = true; break; }
            result.add(room.view());
        }
        return new LocalRoomPage(result, more ? result.get(result.size() - 1).roomId : "");
    }

    /** A read-only page, ordered oldest to newest. Cursor is an opaque anchor within this exact room. */
    public synchronized LocalHistoryPage openLocalRoom(String expectedOrigin, String expectedAccount,
                                                       String roomId, String expectedPeer,
                                                       String beforeMessageCursor, int limit) throws Exception {
        id(roomId); id(expectedPeer); require(!expectedPeer.equals(expectedAccount), "self_contact");
        require(beforeMessageCursor != null && limit >= 1 && limit <= 200, "archive_page_limit");
        State s = archiveState(expectedOrigin, expectedAccount);
        LocalRoomIndex selected = archiveIndex(s).get(roomId);
        require(selected != null && selected.peer.equals(expectedPeer), "unknown_local_room");
        ArchiveMessage anchor = null;
        if (!beforeMessageCursor.isEmpty()) {
            String key = archiveCursorKey(beforeMessageCursor); byte[] bytes = s.get(key);
            require(bytes != null, "archive_cursor"); anchor = archiveMessage(s, key, bytes);
            require(anchor.stored.envelope.roomId.equals(roomId) && anchor.peer.equals(expectedPeer), "archive_cursor");
        }
        // Keep at most one extra record to signal more history; state itself is capped at 16 MiB/12k records.
        PriorityQueue<ArchiveMessage> latest = new PriorityQueue<>(limit + 1, SignalClient::compareArchive);
        for (Map.Entry<String,byte[]> entry : s.records.entrySet()) {
            if (!entry.getKey().startsWith("in|") && !entry.getKey().startsWith("out|")) continue;
            ArchiveMessage message = archiveMessage(s, entry.getKey(), entry.getValue());
            if (!message.stored.envelope.roomId.equals(roomId) || !message.peer.equals(expectedPeer)
                    || anchor != null && compareArchive(message, anchor) >= 0) continue;
            latest.add(message); if (latest.size() > limit + 1) latest.poll();
        }
        boolean more = latest.size() > limit; if (more) latest.poll();
        List<ArchiveMessage> ordered = new ArrayList<>(latest); Collections.sort(ordered, SignalClient::compareArchive);
        List<Incoming> messages = new ArrayList<>();
        for (ArchiveMessage item : ordered) {
            Stored m = item.stored;
            messages.add(new Incoming(m.envelope, m.text, false, item.outgoing && m.accepted, m.createdAtMillis,
                    item.outgoing && s.get("retired-out|" + m.envelope.messageId) != null));
        }
        String next = more ? archiveCursor(ordered.get(0)) : "";
        return new LocalHistoryPage(selected.view(), messages, next);
    }

    private State archiveState(String expectedOrigin, String expectedAccount) throws IOException {
        origin(expectedOrigin); id(expectedAccount); State s = load();
        if (s.records.isEmpty()) return s;
        require(expectedOrigin.equals(s.text("meta|origin")) && expectedAccount.equals(s.text("meta|account"))
                && s.get("local|identity") != null, "archive_profile_mismatch");
        id(s.text("meta|device")); return s;
    }
    private static final class LocalRoomIndex {
        final String room, peer; int count; long latest;
        LocalRoomIndex(String r, String p) { room = r; peer = p; }
        LocalRoom view() { return new LocalRoom(room, peer, count, latest); }
        void add(long time) { latest = count == 0 ? time : Math.max(latest, time); count++; }
    }
    private static LocalRoomIndex archiveRoom(TreeMap<String,LocalRoomIndex> index, String room, String peer) {
        id(room); id(peer); LocalRoomIndex existing = index.get(room);
        require(existing == null || existing.peer.equals(peer), "archive_room_conflict");
        if (existing == null) { existing = new LocalRoomIndex(room, peer); index.put(room, existing); }
        return existing;
    }
    private static TreeMap<String,LocalRoomIndex> archiveIndex(State s) throws IOException {
        TreeMap<String,LocalRoomIndex> index = new TreeMap<>();
        for (Map.Entry<String,byte[]> entry : s.records.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("room|")) {
                String[] pair = s.text(key).split("\\|", -1); require(pair.length == 2, "archive_room_binding");
                id(pair[0]); id(pair[1]); String protocol = s.text("protocol|" + pair[0]);
                require(!pair[0].equals(s.text("meta|account")) && pair[1].equals(s.text("peer|" + pair[0]))
                        && protocol.matches("[1-9][0-9]{0,2}") && Integer.parseInt(protocol) <= 127
                        && s.get("identity|" + pair[0] + "|" + protocol) != null, "archive_room_binding");
                archiveRoom(index, key.substring(5), pair[0]);
            } else if (key.startsWith("in|") || key.startsWith("out|")) {
                ArchiveMessage message = archiveMessage(s, key, entry.getValue());
                archiveRoom(index, message.stored.envelope.roomId, message.peer).add(message.stored.createdAtMillis);
            }
        }
        return index;
    }
    private static final class ArchiveMessage {
        final Stored stored; final String key, peer; final boolean outgoing;
        ArchiveMessage(Stored s, String k, String p, boolean out) { stored = s; key = k; peer = p; outgoing = out; }
    }
    private static ArchiveMessage archiveMessage(State s, String key, byte[] bytes) throws IOException {
        boolean outgoing = key.startsWith("out|"); require(outgoing || key.startsWith("in|"), "archive_cursor");
        Stored m = decodeStored(bytes); Envelope e = m.envelope; validateEnvelope(e); checkText(m.text);
        require(key.equals((outgoing ? "out|" : "in|") + e.messageId), "archive_message_binding");
        String ownAccount = outgoing ? e.senderAccountId : e.recipientAccountId;
        String ownDevice = outgoing ? e.senderDeviceId : e.recipientDeviceId;
        String peer = outgoing ? e.recipientAccountId : e.senderAccountId;
        require(ownAccount.equals(s.text("meta|account")) && ownDevice.equals(s.text("meta|device"))
                && !peer.equals(ownAccount), "archive_message_binding");
        return new ArchiveMessage(m, key, peer, outgoing);
    }
    private static int compareArchive(ArchiveMessage left, ArchiveMessage right) {
        int result = Long.compare(left.stored.createdAtMillis, right.stored.createdAtMillis);
        if (result == 0) result = left.stored.envelope.messageId.compareTo(right.stored.envelope.messageId);
        return result == 0 ? left.key.compareTo(right.key) : result;
    }
    private static String archiveCursor(ArchiveMessage message) { return (message.outgoing ? "out:" : "in:") + message.stored.envelope.messageId; }
    private static String archiveCursorKey(String cursor) {
        require(cursor.startsWith("out:") || cursor.startsWith("in:"), "archive_cursor");
        boolean outgoing = cursor.startsWith("out:"); String message = cursor.substring(outgoing ? 4 : 3); id(message);
        return (outgoing ? "out|" : "in|") + message;
    }

    private State initialized() throws IOException { State s = load(); require(s.get("local|identity") != null, "not_initialized"); return s; }
    private State bound() throws IOException { State s = initialized(); require(!s.text("meta|account").isEmpty(), "account_unbound"); return s; }
    private State load() throws IOException { byte[] bytes = storage.load(); return bytes == null ? new State() : State.decode(bytes); }
    private void commit(State s) throws IOException { storage.compareAndSave(s.original, s.encode()); }
    private static SessionCipher cipher(State s, String peer) { return new SessionCipher(s, address(s, s.text("meta|account")), address(s, peer)); }
    private static SignalProtocolAddress address(State s, String account) {
        String protocol = account.equals(s.text("meta|account")) ? s.text("meta|protocol") : s.text("protocol|" + account);
        require(!protocol.isEmpty(), "peer_protocol_unpinned"); return new SignalProtocolAddress(account, Integer.parseInt(protocol));
    }
    private static void bindPeer(State s, String room, String account, String device) {
        String oldDevice = s.text("peer|" + account); require(oldDevice.isEmpty() || oldDevice.equals(device), "peer_device_changed");
        String binding = account + "|" + device, oldRoom = s.text("room|" + room);
        require(oldRoom.isEmpty() || oldRoom.equals(binding), "room_binding_changed");
        s.text("peer|" + account, device); s.text("room|" + room, binding);
    }
    private static void requirePeer(State s, String room, String account, String device) {
        require((account + "|" + device).equals(s.text("room|" + room)) && device.equals(s.text("peer|" + account)), "unbound_peer");
        require(s.getIdentity(address(s, account)) != null, "identity_unpinned");
    }
    private static void validateBundle(Bundle b) {
        require(b != null && b.registrationId >= 1 && b.registrationId <= 16383 && b.protocolDeviceId >= 1 && b.protocolDeviceId <= 127, "bundle_identifiers");
        keyBytes(b.identityKeyBase64, 33, 5);
        require(b.signedPrekey != null && b.oneTimePrekey != null && b.kyberPrekey != null, "missing_prekey");
        require(b.signedPrekey.keyId > 0 && b.oneTimePrekey.keyId > 0 && b.kyberPrekey.keyId > 0, "prekey_identifier");
        keyBytes(b.signedPrekey.publicKeyBase64, 33, 5); keyBytes(b.oneTimePrekey.publicKeyBase64, 33, 5); keyBytes(b.kyberPrekey.publicKeyBase64, 1569, 8);
        require(b64(b.signedPrekey.signatureBase64).length == 64 && b64(b.kyberPrekey.signatureBase64).length == 64, "signature_encoding");
    }
    private static void keyBytes(String encoded, int length, int tag) { byte[] b = b64(encoded); require(b.length == length && b[0] == tag, "key_encoding"); }
    private static void validateEnvelope(Envelope e) {
        require(e != null, "missing_envelope"); id(e.roomId); id(e.messageId); id(e.senderAccountId); id(e.senderDeviceId); id(e.recipientAccountId); id(e.recipientDeviceId);
        require(e.type == CiphertextMessage.PREKEY_TYPE || e.type == CiphertextMessage.WHISPER_TYPE, "unsupported_ciphertext_type");
        int length = b64(e.bodyBase64).length; require(length > 0 && length <= 96 * 1024, "ciphertext_size");
    }
    private static void origin(String value) { require(value != null && value.matches("http://[a-z2-7]{56}\\.onion"), "invalid_origin"); }
    private static void id(String value) { require(value != null && value.length() >= 6 && value.length() <= 80 && value.matches("demo-[a-z0-9-]+"), "invalid_identifier"); }
    /** Pure UI/staging validation. Preserve the exact text; never normalize or replace invalid Unicode. */
    public static void checkText(String text) {
        require(text != null && !text.trim().isEmpty() && text.length() <= MAX_TEXT_BYTES, "text_size");
        try {
            int bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(text)).remaining();
            require(bytes <= MAX_TEXT_BYTES, "text_size");
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw invalid("text_encoding");
        }
    }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
    private static void require(boolean ok, String code) { if (!ok) throw invalid(code); }
    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    private static byte[] b64(String s) {
        require(s != null && s.length() <= 128 * 1024, "base64_size");
        byte[] b = Base64.getDecoder().decode(s); require(b64(b).equals(s), "noncanonical_base64"); return b;
    }
    private static boolean sameContext(Envelope a, Envelope b) {
        return a.roomId.equals(b.roomId) && a.messageId.equals(b.messageId) && a.senderAccountId.equals(b.senderAccountId)
                && a.senderDeviceId.equals(b.senderDeviceId) && a.recipientAccountId.equals(b.recipientAccountId) && a.recipientDeviceId.equals(b.recipientDeviceId);
    }
    private static boolean sameEnvelope(Envelope a, Envelope b) { return sameContext(a, b) && a.type == b.type && a.bodyBase64.equals(b.bodyBase64); }
    private static void writeContext(DataOutputStream out, Envelope e) throws IOException {
        out.writeUTF(e.roomId); out.writeUTF(e.messageId); out.writeUTF(e.senderAccountId); out.writeUTF(e.senderDeviceId); out.writeUTF(e.recipientAccountId); out.writeUTF(e.recipientDeviceId);
    }
    private static Envelope readContext(DataInputStream in) throws IOException { return new Envelope(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), 0, ""); }
    private static byte[] frame(Envelope e, String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x54545031); out.writeByte(1); writeContext(out, e); out.writeUTF(text); out.flush(); return bytes.toByteArray();
    }
    private static String parseFrame(byte[] bytes, Envelope e) throws IOException {
        DataInputStream in = input(bytes); require(in.readInt() == 0x54545031 && in.readUnsignedByte() == 1, "unknown_inner_frame");
        require(sameContext(readContext(in), e), "inner_context_mismatch"); String text = in.readUTF(); checkText(text); require(in.available() == 0, "inner_trailing_bytes"); return text;
    }
    private static final class Stored {
        final Envelope envelope; final String text; final boolean accepted; final long createdAtMillis;
        Stored(Envelope e, String t, boolean a) { this(e, t, a, System.currentTimeMillis()); }
        Stored(Envelope e, String t, boolean a, long createdAt) { envelope = e; text = t; accepted = a; createdAtMillis = createdAt; }
    }
    private static byte[] encodeStored(Stored m) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        writeContext(out, m.envelope); out.writeInt(m.envelope.type); writeBlob(out, b64(m.envelope.bodyBase64)); out.writeUTF(m.text); out.writeBoolean(m.accepted); out.writeLong(m.createdAtMillis); out.flush(); return bytes.toByteArray();
    }
    private static Stored decodeStored(byte[] bytes) throws IOException {
        DataInputStream in = input(bytes); Envelope c = readContext(in);
        Envelope e = new Envelope(c.roomId, c.messageId, c.senderAccountId, c.senderDeviceId, c.recipientAccountId, c.recipientDeviceId, in.readInt(), b64(readBlob(in)));
        Stored m = new Stored(e, in.readUTF(), in.readBoolean(), in.readLong()); require(in.available() == 0, "stored_trailing_bytes"); return m;
    }
    private static void writeKey(DataOutputStream out, PublicKey key) throws IOException { out.writeInt(key.keyId); out.writeUTF(key.publicKeyBase64); out.writeUTF(key.signatureBase64 == null ? "" : key.signatureBase64); }
    private static PublicKey readKey(DataInputStream in) throws IOException { int id = in.readInt(); String pub = in.readUTF(), sig = in.readUTF(); return new PublicKey(id, pub, sig.isEmpty() ? null : sig); }
    private static byte[] encodePublication(Publication p) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(p.registrationId); out.writeInt(p.protocolDeviceId); out.writeUTF(p.identityKeyBase64); writeKey(out, p.signedPrekey); out.writeInt(p.oneTimePrekeys.size());
        for (int i = 0; i < p.oneTimePrekeys.size(); i++) { writeKey(out, p.oneTimePrekeys.get(i)); writeKey(out, p.kyberPrekeys.get(i)); } out.flush(); return bytes.toByteArray();
    }
    private static Publication decodePublication(byte[] bytes) throws IOException {
        DataInputStream in = input(bytes); int registration = in.readInt(); int protocol = in.readInt(); String identity = in.readUTF(); PublicKey signed = readKey(in); int count = in.readInt();
        require(count >= 1 && count <= 20, "stored_publication_count"); List<PublicKey> ec = new ArrayList<>(), kyber = new ArrayList<>();
        for (int i = 0; i < count; i++) { ec.add(readKey(in)); kyber.add(readKey(in)); }
        require(in.available() == 0, "stored_publication_trailing_bytes"); return new Publication(registration, protocol, identity, signed, ec, kyber);
    }
    private static byte[] encodeReplenishment(Replenishment r) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(r.batchId); out.writeInt(r.oneTimePrekeys.size());
        for (int i = 0; i < r.oneTimePrekeys.size(); i++) { writeKey(out, r.oneTimePrekeys.get(i)); writeKey(out, r.kyberPrekeys.get(i)); }
        out.flush(); return bytes.toByteArray();
    }
    private static Replenishment decodeReplenishment(byte[] bytes) throws IOException {
        DataInputStream in = input(bytes); String id = in.readUTF(); int count = in.readInt(); require(count >= 1 && count <= 20, "stored_refill_count");
        List<PublicKey> ec = new ArrayList<>(), kyber = new ArrayList<>();
        for (int i = 0; i < count; i++) { ec.add(readKey(in)); kyber.add(readKey(in)); }
        require(in.available() == 0, "stored_refill_trailing_bytes"); return new Replenishment(id, ec, kyber);
    }
    private static DataInputStream input(byte[] bytes) { require(bytes != null, "missing_record"); return new DataInputStream(new ByteArrayInputStream(bytes)); }
    private static void writeBlob(DataOutputStream out, byte[] b) throws IOException { out.writeInt(b.length); out.write(b); }
    private static byte[] readBlob(DataInputStream in) throws IOException {
        int length = in.readInt(); require(length >= 0 && length <= 2 * 1024 * 1024 && length <= in.available(), "record_size"); byte[] b = new byte[length]; in.readFully(b); return b;
    }

    /** Copy-on-operation store. All JNI callbacks mutate only this disposable snapshot. */
    private static final class State implements SignalProtocolStore {
        final TreeMap<String, byte[]> records = new TreeMap<>();
        byte[] original;
        byte[] get(String key) { return records.get(key); }
        void put(String key, byte[] value) { records.put(key, value.clone()); }
        void remove(String key) { records.remove(key); }
        String text(String key) { byte[] b = get(key); return b == null ? "" : new String(b, StandardCharsets.UTF_8); }
        void text(String key, String value) { put(key, value.getBytes(StandardCharsets.UTF_8)); }
        List<byte[]> all(String prefix) { List<byte[]> values = new ArrayList<>(); for (Map.Entry<String,byte[]> e : records.entrySet()) if (e.getKey().startsWith(prefix)) values.add(e.getValue()); return values; }
        byte[] encode() throws IOException {
            require(records.size() <= MAX_RECORDS, "state_capacity"); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x54545343); out.writeInt(1); out.writeInt(records.size());
            for (Map.Entry<String,byte[]> e : records.entrySet()) { out.writeUTF(e.getKey()); writeBlob(out, e.getValue()); if (bytes.size() > MAX_STATE) throw invalid("state_capacity"); }
            out.flush(); return bytes.toByteArray();
        }
        static State decode(byte[] bytes) throws IOException {
            require(bytes.length <= MAX_STATE, "state_capacity"); DataInputStream in = input(bytes);
            require(in.readInt() == 0x54545343 && in.readInt() == 1, "state_version"); int count = in.readInt(); require(count >= 0 && count <= MAX_RECORDS, "state_count"); State s = new State();
            for (int i = 0; i < count; i++) { String key = in.readUTF(); require(key.length() <= 256 && !s.records.containsKey(key), "state_duplicate_key"); s.put(key, readBlob(in)); }
            require(in.available() == 0, "state_trailing_bytes"); s.original = bytes.clone(); return s;
        }
        interface Decoder<T> { T decode(byte[] bytes) throws Exception; }
        static <T> T decode(byte[] bytes, Decoder<T> decoder) { try { return decoder.decode(bytes); } catch (Exception e) { throw new IllegalStateException("invalid_signal_record", e); } }
        String key(String kind, SignalProtocolAddress a) { return kind + "|" + a.getName() + "|" + a.getDeviceId(); }
        byte[] required(String kind, int id) throws InvalidKeyIdException { byte[] b = get(kind + "|" + id); if (b == null) throw new InvalidKeyIdException("missing_prekey"); return b; }
        public IdentityKeyPair getIdentityKeyPair() { return decode(get("local|identity"), IdentityKeyPair::new); }
        public int getLocalRegistrationId() { return Integer.parseInt(text("local|registration")); }
        public IdentityKey getIdentity(SignalProtocolAddress a) { byte[] b = get(key("identity", a)); return b == null ? null : decode(b, IdentityKey::new); }
        public IdentityChange saveIdentity(SignalProtocolAddress a, IdentityKey identity) {
            IdentityKey old = getIdentity(a); require(old == null || old.equals(identity), "identity_changed"); put(key("identity", a), identity.serialize()); return IdentityChange.NEW_OR_UNCHANGED;
        }
        public boolean isTrustedIdentity(SignalProtocolAddress a, IdentityKey identity, Direction direction) { IdentityKey old = getIdentity(a); return old != null && old.equals(identity); }
        public SessionRecord loadSession(SignalProtocolAddress a) { byte[] b = get(key("session", a)); return b == null ? new SessionRecord() : decode(b, SessionRecord::new); }
        public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
            List<SessionRecord> values = new ArrayList<>(); for (SignalProtocolAddress a : addresses) { SessionRecord r = loadSession(a); if (!r.hasSenderChain()) throw new NoSessionException("missing_session"); values.add(r); } return values;
        }
        public List<Integer> getSubDeviceSessions(String name) { return Collections.emptyList(); }
        public void storeSession(SignalProtocolAddress a, SessionRecord session) { put(key("session", a), session.serialize()); }
        public boolean containsSession(SignalProtocolAddress a) { return get(key("session", a)) != null && loadSession(a).hasSenderChain(); }
        public void deleteSession(SignalProtocolAddress a) { remove(key("session", a)); }
        public void deleteAllSessions(String name) { records.keySet().removeIf(k -> k.startsWith("session|" + name + "|")); }
        public PreKeyRecord loadPreKey(int id) throws InvalidKeyIdException { return decode(required("prekey", id), PreKeyRecord::new); }
        public void storePreKey(int id, PreKeyRecord r) { put("prekey|" + id, r.serialize()); }
        public boolean containsPreKey(int id) { return get("prekey|" + id) != null; }
        public void removePreKey(int id) { remove("prekey|" + id); }
        public SignedPreKeyRecord loadSignedPreKey(int id) throws InvalidKeyIdException { return decode(required("signed", id), SignedPreKeyRecord::new); }
        public List<SignedPreKeyRecord> loadSignedPreKeys() { List<SignedPreKeyRecord> result = new ArrayList<>(); for (byte[] b : all("signed|")) result.add(decode(b, SignedPreKeyRecord::new)); return result; }
        public void storeSignedPreKey(int id, SignedPreKeyRecord r) { put("signed|" + id, r.serialize()); }
        public boolean containsSignedPreKey(int id) { return get("signed|" + id) != null; }
        public void removeSignedPreKey(int id) { remove("signed|" + id); }
        public KyberPreKeyRecord loadKyberPreKey(int id) throws InvalidKeyIdException { return decode(required("kyber", id), KyberPreKeyRecord::new); }
        public List<KyberPreKeyRecord> loadKyberPreKeys() { List<KyberPreKeyRecord> result = new ArrayList<>(); for (byte[] b : all("kyber|")) result.add(decode(b, KyberPreKeyRecord::new)); return result; }
        public void storeKyberPreKey(int id, KyberPreKeyRecord r) { put("kyber|" + id, r.serialize()); }
        public boolean containsKyberPreKey(int id) { return get("kyber|" + id) != null; }
        public void markKyberPreKeyUsed(int id, int signedId, ECPublicKey baseKey) { remove("kyber|" + id); }
        public void storeSenderKey(SignalProtocolAddress a, UUID distribution, SenderKeyRecord record) { throw new UnsupportedOperationException("groups_not_supported"); }
        public SenderKeyRecord loadSenderKey(SignalProtocolAddress a, UUID distribution) { throw new UnsupportedOperationException("groups_not_supported"); }
    }
}
