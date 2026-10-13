package dev.gyeopdam.embeddedtor;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict JSON boundary between the pilot relay and the production Signal client. */
public final class SignalJson {
    private SignalJson() { }
    /** Native archive projections contain public associations and local text, never keys or ciphertext. */
    public static JSONObject localRooms(SignalClient.LocalRoomPage page) throws JSONException {
        JSONArray rooms = new JSONArray();
        for (SignalClient.LocalRoom room : page.rooms) rooms.put(new JSONObject().put("room_id",room.roomId)
                .put("peer_id",room.peerAccountId).put("message_count",room.messageCount)
                .put("last_message_at",room.lastMessageAtMillis).put("read_only",true));
        return new JSONObject().put("rooms",rooms).put("next_after",page.nextAfterRoomId);
    }
    public static JSONArray localHistoryMessages(SignalClient.LocalHistoryPage page, String expectedAccount) throws JSONException {
        JSONArray result = new JSONArray();
        for (SignalClient.Incoming entry : page.messages) {
            SignalClient.Envelope e = entry.envelope; boolean own = expectedAccount.equals(e.senderAccountId);
            if (!page.room.roomId.equals(e.roomId)
                    || !(own ? page.room.peerAccountId.equals(e.recipientAccountId)
                    : expectedAccount.equals(e.recipientAccountId) && page.room.peerAccountId.equals(e.senderAccountId))) throw invalid();
            String status = own ? entry.relayAccepted ? "server_accepted" : entry.retired ? "retired_identity" : "pending" : "received_locally";
            result.put(new JSONObject().put("id",e.messageId).put("text",entry.text).put("own",own)
                    .put("local_time",entry.createdAtMillis).put("status",status));
        }
        return result;
    }
    public static JSONObject publication(SignalClient.Publication p) throws JSONException {
        JSONArray ec = new JSONArray(), kyber = new JSONArray();
        for (SignalClient.PublicKey k : p.oneTimePrekeys) ec.put(publicKey(k));
        for (SignalClient.PublicKey k : p.kyberPrekeys) kyber.put(publicKey(k));
        return new JSONObject().put("registration_id", p.registrationId).put("protocol_device_id", p.protocolDeviceId)
                .put("identity_key_base64", p.identityKeyBase64).put("signed_prekey", publicKey(p.signedPrekey))
                .put("one_time_prekeys", ec).put("kyber_prekeys", kyber);
    }
    public static JSONObject replenishment(SignalClient.Replenishment batch) throws JSONException {
        JSONArray ec = new JSONArray(), kyber = new JSONArray();
        for (SignalClient.PublicKey key : batch.oneTimePrekeys) ec.put(publicKey(key));
        for (SignalClient.PublicKey key : batch.kyberPrekeys) kyber.put(publicKey(key));
        return new JSONObject().put("batch_id", batch.batchId).put("one_time_prekeys", ec).put("kyber_prekeys", kyber);
    }
    public static JSONObject publicKey(SignalClient.PublicKey key) throws JSONException {
        JSONObject result = new JSONObject().put("key_id", key.keyId).put("public_key_base64", key.publicKeyBase64);
        if (key.signatureBase64 != null) result.put("signature_base64", key.signatureBase64);
        return result;
    }
    public static JSONObject claimRequest(String claimId, String localDeviceId, String peerDeviceId) throws JSONException {
        return new JSONObject().put("claim_id", claimId).put("device_id", localDeviceId).put("recipient_device_id", peerDeviceId);
    }
    public static SignalClient.Bundle claimedBundle(JSONObject response, String claimId, String roomId,
                                                     String peerAccountId, String peerDeviceId, String expectedIdentityBase64) throws Exception {
        equal(response, "claim_id", claimId); equal(response, "room_id", roomId); equal(response, "account_id", peerAccountId); equal(response, "device_id", peerDeviceId);
        equal(response, "identity_key_base64", expectedIdentityBase64); equal(response, "key_digest", keyDigest(expectedIdentityBase64));
        return new SignalClient.Bundle(integer(response, "registration_id"), integer(response, "protocol_device_id"), string(response, "identity_key_base64"),
                key(response.getJSONObject("signed_prekey"), true), key(response.getJSONObject("one_time_prekey"), false), key(response.getJSONObject("kyber_prekey"), true));
    }
    /** Idempotency key is the already-durable unique message id; never mint it on retry. */
    public static JSONObject sendRequest(SignalClient client, SignalClient.Envelope message) throws Exception {
        client.validateOutboundEnvelope(message);
        if (!message.senderAccountId.equals(client.accountId()) || !message.senderDeviceId.equals(client.deviceId())) throw invalid();
        JSONObject envelope = new JSONObject().put("version", 1).put("suite", "signal-pq-v1")
                .put("sender_id", message.senderAccountId).put("sender_device_id", message.senderDeviceId)
                .put("sender_key_digest", keyDigest(client.identityKeyBase64()))
                .put("recipient_id", message.recipientAccountId).put("recipient_device_id", message.recipientDeviceId)
                .put("recipient_key_digest", keyDigest(client.pinnedIdentityKeyBase64(message.recipientAccountId)))
                .put("ciphertext_base64", message.wireBase64());
        return new JSONObject().put("message_id", message.messageId).put("idempotency_key", message.messageId).put("envelope", envelope);
    }
    /** Validate requested receipt and pinned identities before libsignal handles the ciphertext. */
    public static SignalClient.Envelope receivedEnvelope(SignalClient client, JSONObject response, String roomId, String messageId) throws Exception {
        JSONObject receipt = response.getJSONObject("receipt"), e = response.getJSONObject("envelope");
        equal(receipt, "room_id", roomId); equal(receipt, "message_id", messageId);
        if (integer(e, "version") != 1) throw invalid(); equal(e, "suite", "signal-pq-v1");
        String sender = string(e, "sender_id"), recipient = string(e, "recipient_id");
        equal(receipt, "sender_id", sender); equal(receipt, "recipient_id", recipient);
        equal(e, "recipient_id", client.accountId()); equal(e, "recipient_device_id", client.deviceId());
        equal(e, "sender_key_digest", keyDigest(client.pinnedIdentityKeyBase64(sender)));
        equal(e, "recipient_key_digest", keyDigest(client.identityKeyBase64()));
        return SignalClient.Envelope.fromWire(roomId, messageId, sender, string(e, "sender_device_id"), recipient,
                string(e, "recipient_device_id"), string(e, "ciphertext_base64"));
    }
    /** Backend digests the raw32 identity key, while libsignal transports05+32. */
    public static String keyDigest(String serializedIdentityBase64) throws Exception {
        if (serializedIdentityBase64 == null) throw invalid();
        byte[] identity = Base64.getDecoder().decode(serializedIdentityBase64);
        if (identity.length != 33 || identity[0] != 5 || !Base64.getEncoder().encodeToString(identity).equals(serializedIdentityBase64)) throw invalid();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(identity, 1, identity.length));
        StringBuilder out = new StringBuilder(64); for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    private static SignalClient.PublicKey key(JSONObject value, boolean signed) throws JSONException {
        return new SignalClient.PublicKey(integer(value, "key_id"), string(value, "public_key_base64"), signed ? string(value, "signature_base64") : null);
    }
    private static int integer(JSONObject value, String key) throws JSONException {
        Object result = value.get(key); if (!(result instanceof Number)) throw invalid();
        double number = ((Number) result).doubleValue(); int integer = ((Number) result).intValue();
        if (!Double.isFinite(number) || number != integer || integer < 1) throw invalid(); return integer;
    }
    private static String string(JSONObject value, String key) throws JSONException {
        Object result = value.get(key); if (!(result instanceof String)) throw invalid(); return (String) result;
    }
    private static void equal(JSONObject value, String key, String expected) throws JSONException { if (expected == null || !string(value, key).equals(expected)) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("signal_wire_binding"); }
}
