package dev.gyeopdam.embeddedtor;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** TEST ONLY. Synthetic peer state is plaintext in a disposable private test directory. */
public final class SignalPilotPeer {
    public static void main(String[] args) {
        try {
            Path file = Paths.get(args[0]); String command = args[1];
            JSONObject q = new JSONObject(new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            SignalClient client = new SignalClient(new SignalClient.StateStorage() {
                public byte[] load() throws IOException { return Files.exists(file) ? Files.readAllBytes(file) : null; }
                public void save(byte[] state) throws IOException {
                    Path tmp = file.resolveSibling(file.getFileName() + ".next");
                    try (FileOutputStream out = new FileOutputStream(tmp.toFile())) { out.write(state); out.getFD().sync(); }
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    try (FileChannel dir = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { dir.force(true); }
                }
            });
            JSONObject result = new JSONObject();
            switch (command) {
                case "init": {
                    Set<Integer> excluded = new HashSet<>();
                    JSONArray protocols = q.optJSONArray("excluded_protocol_ids");
                    if (protocols != null) for (int i = 0; i < protocols.length(); i++) excluded.add(protocols.getInt(i));
                    client.prepareIdentity(q.getString("origin"), q.getString("device_id"), q.optInt("count", 3), excluded);
                    client.bindAccount(q.getString("account_id"));
                    result.put("identity_key_base64", client.identityKeyBase64()).put("public_key_base64", client.devicePublicKeyBase64())
                            .put("protocol_device_id", client.protocolDeviceId()).put("device_token", client.prepareDeviceToken()).put("publication", SignalJson.publication(client.publication())); break;
                }
                case "pin":
                    client.acceptPeer(q.getString("room_id"), q.getString("account_id"), q.getString("device_id"), q.getInt("protocol_device_id"), q.getString("identity_key_base64")); break;
                case "replace-pin":
                    client.replacePeerIdentity(q.getString("room_id"), q.getString("account_id"), q.getString("device_id"), q.getInt("protocol_device_id"), q.getString("identity_key_base64"), q.getString("expected_old_identity_key_base64")); break;
                case "peer-status":
                    result.put("identity_key_base64", client.pinnedIdentityKeyBase64(q.getString("account_id")))
                            .put("has_session", client.hasSession(q.getString("account_id"), q.getString("device_id"))); break;
                case "claim": result.put("claim_id", client.claimId(q.getString("room_id"), q.getString("device_id"))); break;
                case "accept": {
                    String peer = q.getString("account_id"), room = q.getString("room_id"), device = q.getString("device_id");
                    SignalClient.Bundle bundle = SignalJson.claimedBundle(q.getJSONObject("bundle"), q.getString("claim_id"), room, peer, device, client.pinnedIdentityKeyBase64(peer));
                    client.acceptBundle(room, peer, device, bundle); break;
                }
                case "send": result = SignalJson.sendRequest(client, client.stageText(q.getString("room_id"), q.getString("account_id"), q.getString("device_id"), q.getString("message_id"), q.getString("text"))); break;
                case "emit-stored":
                case "validate-stored": {
                    SignalClient.Envelope stored = null;
                    for (SignalClient.Incoming message : client.sentMessages()) {
                        if (message.envelope.messageId.equals(q.getString("message_id"))) stored = message.envelope;
                    }
                    if (stored == null) throw new IllegalArgumentException("unknown_test_message");
                    if (command.equals("emit-stored")) result = SignalJson.sendRequest(client, stored);
                    else client.validateOutboundEnvelope(stored);
                    break;
                }
                case "receive": {
                    SignalClient.Envelope e = SignalJson.receivedEnvelope(client, q.getJSONObject("message"), q.getString("room_id"), q.getString("message_id"));
                    SignalClient.Incoming r = client.receive(e); result.put("text", r.text).put("duplicate", r.duplicate).put("safe_to_ack", true); break;
                }
                case "accepted": client.markRelayAccepted(q.getString("message_id")); break;
                case "history":
                    result.put("inbox_count", client.inbox().size()).put("outbox_count", client.sentMessages().size()).put("pending_count", client.pendingOutbox().size());
                    if (q.optBoolean("details", false)) {
                        JSONArray pending = new JSONArray();
                        for (SignalClient.Envelope message : client.pendingOutbox()) pending.put(message.messageId);
                        result.put("inbox", history(client.inbox())).put("outbox", history(client.sentMessages())).put("pending", pending);
                    }
                    break;
                case "refill": result = SignalJson.replenishment(client.prepareReplenishment(q.getInt("count"))); break;
                case "pending-refill": {
                    SignalClient.Replenishment pending = client.pendingReplenishment();
                    result.put("pending", pending == null ? JSONObject.NULL : SignalJson.replenishment(pending)); break;
                }
                case "refill-accepted": client.confirmReplenishment(q.getString("batch_id")); break;
                default: throw new IllegalArgumentException("unknown_test_command");
            }
            System.out.println(result);
        } catch (Exception failure) {
            System.err.println("Signal pilot test operation rejected"); System.exit(1);
        }
    }

    private static JSONArray history(List<SignalClient.Incoming> messages) throws Exception {
        JSONArray result = new JSONArray();
        for (SignalClient.Incoming message : messages) {
            SignalClient.Envelope envelope = message.envelope;
            result.put(new JSONObject().put("message_id", envelope.messageId).put("text", message.text)
                    .put("room_id", envelope.roomId).put("sender_device_id", envelope.senderDeviceId)
                    .put("recipient_device_id", envelope.recipientDeviceId).put("ciphertext_base64", envelope.wireBase64())
                    .put("relay_accepted", message.relayAccepted).put("retired", message.retired)
                    .put("created_at", message.createdAtMillis));
        }
        return result;
    }
}
