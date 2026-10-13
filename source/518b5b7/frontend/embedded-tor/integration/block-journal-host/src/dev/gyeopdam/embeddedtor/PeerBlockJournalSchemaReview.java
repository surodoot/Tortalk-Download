package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure synthetic validation: no disk, network, Android operations, or key generation. */
public final class PeerBlockJournalSchemaReview {
    private static final String ORIGIN = "http://" + "a".repeat(56) + ".onion";
    private static final String ACCOUNT = "demo-account-a";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        roundTrip();
        canonicalOrdering();
        bounds();
        inputValidation();
        malformedBytes();
        statusRoundTripAndBounds();
        malformedStatuses();
        namespace();
        System.out.println("PASS: " + assertions + " pure block-journal schema assertions");
    }

    private static void roundTrip() throws Exception {
        List<PeerBlockJournal.Pending> pending = Arrays.asList(
                new PeerBlockJournal.Pending("demo-peer-a", "demo-mutation-a", 0, true),
                new PeerBlockJournal.Pending("demo-peer-b", "demo-mutation-b", Integer.MAX_VALUE, false));
        byte[] bytes = encode(pending);
        PeerBlockJournalSchema.Record restored = PeerBlockJournalSchema.decode(bytes);
        check(restored.origin.equals(ORIGIN) && restored.account.equals(ACCOUNT), "Binding did not round-trip");
        check(restored.pending.size() == pending.size(), "Wrong pending count");
        for (int i = 0; i < pending.size(); i++) same(pending.get(i), restored.pending.get(i));
        equal(bytes, PeerBlockJournalSchema.encode(restored.origin, restored.account, restored.pending));
        check(PeerBlockJournalSchema.decode(encode(Collections.emptyList())).pending.isEmpty(), "Empty journal failed");
        try { restored.pending.clear(); fail("Mutable decoded snapshot"); }
        catch (UnsupportedOperationException expected) { assertions++; }
        check(new PeerBlockJournal.Pending("demo-peer-a", "demo-operation-a", 1, true)
                .toString().equals("PendingBlockRequest([redacted])"), "Pending printed metadata");
    }

    private static void canonicalOrdering() throws Exception {
        PeerBlockJournal.Pending a = pending("demo-peer-a"), b = pending("demo-peer-b");
        equal(encode(Arrays.asList(a, b)), encode(Arrays.asList(b, a)));
        rejectEncode(ORIGIN, ACCOUNT, Arrays.asList(a, a));
        byte[] encoded = encode(Arrays.asList(a, b));
        int first = entryStart(encoded), length = entryEnd(encoded, first) - first;
        byte[] unordered = encoded.clone();
        System.arraycopy(encoded, first + length, unordered, first, length);
        System.arraycopy(encoded, first, unordered, first + length, length);
        rejectDecode(unordered);
        byte[] duplicate = encoded.clone();
        System.arraycopy(encoded, first, duplicate, first + length, length);
        rejectDecode(duplicate);
    }

    private static void bounds() throws Exception {
        List<PeerBlockJournal.Pending> entries = new ArrayList<>();
        for (int i = 0; i < 8; i++) entries.add(new PeerBlockJournal.Pending(
                "demo-" + (char) ('a' + i) + "p".repeat(74), "demo-" + "m".repeat(75), Integer.MAX_VALUE, i % 2 == 0));
        byte[] largest = PeerBlockJournalSchema.encode(ORIGIN, "demo-" + "z".repeat(75), entries);
        check(largest.length < 4096, "Eight maximum-sized requests exceeded 4 KiB");
        check(PeerBlockJournalSchema.decode(largest).pending.size() == 8, "Eight requests were not preserved");
        entries.add(pending("demo-peer-nine"));
        rejectEncode(ORIGIN, ACCOUNT, entries);
        rejectEncode(ORIGIN, ACCOUNT, null);
        rejectEncode(ORIGIN, ACCOUNT, Arrays.asList((PeerBlockJournal.Pending) null));
        rejectDecode(null);
        rejectDecode(new byte[PeerBlockJournalSchema.MAX_BYTES + 1]);
        for (int count : new int[] { -1, 9, 255, Integer.MAX_VALUE }) {
            byte[] bytes = encode(Collections.emptyList());
            ByteBuffer.wrap(bytes).putInt(8, count); rejectDecode(bytes);
        }
    }

    private static void inputValidation() throws Exception {
        for (String value : new String[] { null, "", "demo-", "Demo-peer", "peer-demo", "demo_UP", "demo-abc_",
                "demo-abc\n", "demo-한글", "demo-../path", "demo-" + "x".repeat(76) }) {
            rejectEncode(ORIGIN, value, Collections.emptyList());
            rejectInput(() -> new PeerBlockJournal.Pending(value, "demo-operation", 1, true));
            rejectInput(() -> new PeerBlockJournal.Pending("demo-peer", value, 1, true));
        }
        for (String value : new String[] { null, "", ORIGIN + "/", ORIGIN + ":80", ORIGIN + "/v1",
                ORIGIN.toUpperCase(), ORIGIN.replace("http://", "https://"),
                ORIGIN.replace("http://", "http://name@"), "http://example.com",
                "http://" + "a".repeat(16) + ".onion", "http://" + "0".repeat(56) + ".onion" }) {
            rejectEncode(value, ACCOUNT, Collections.emptyList());
            rejectInput(() -> PeerBlockJournal.namespace(value, ACCOUNT));
        }
        rejectInput(() -> new PeerBlockJournal.Pending("demo-peer", "demo-operation", -1, true));
        rejectInput(() -> new PeerBlockJournal.Pending("demo-peer", "demo-operation", Integer.MIN_VALUE, false));
        rejectEncode(ORIGIN, ACCOUNT, Collections.singletonList(pending(ACCOUNT)));
        check(PeerBlockJournalSchema.validId("demo--"), "Backend-valid hyphen suffix rejected");
        check(PeerBlockJournalSchema.validId("demo-a"), "Minimum ID rejected");
    }

    private static void malformedBytes() throws Exception {
        byte[] valid = encode(Collections.singletonList(pending("demo-peer-a")));
        for (int length = 0; length < valid.length; length++) rejectDecode(Arrays.copyOf(valid, length));
        rejectDecode(Arrays.copyOf(valid, valid.length + 1));
        for (int offset : new int[] { 0, 4 }) {
            byte[] bytes = valid.clone(); ByteBuffer.wrap(bytes).putInt(offset, 2); rejectDecode(bytes);
        }
        int start = entryStart(valid);
        int mutation = skipAscii(valid, start);
        int revision = skipAscii(valid, mutation);
        for (int offset : new int[] { 16, skipAscii(valid, 16), start, mutation }) {
            for (int length : new int[] { 0, 81, 65535 }) {
                byte[] bytes = valid.clone(); ByteBuffer.wrap(bytes).putShort(offset, (short) length); rejectDecode(bytes);
            }
            for (int c : new int[] { 0, 10, 32, 127, 255 }) {
                byte[] bytes = valid.clone(); bytes[offset + 2] = (byte) c; rejectDecode(bytes);
            }
        }
        for (int value : new int[] { -1, Integer.MIN_VALUE }) {
            byte[] bytes = valid.clone(); ByteBuffer.wrap(bytes).putInt(revision, value); rejectDecode(bytes);
        }
        for (int value : new int[] { -1, 2, 127 }) {
            byte[] bytes = valid.clone(); bytes[revision + 4] = (byte) value; rejectDecode(bytes);
        }
        // IDs with valid ASCII but an invalid required prefix must fail decoded validation too.
        byte[] badMutation = valid.clone(); badMutation[mutation + 2] = 'x'; rejectDecode(badMutation);
        byte[] badPeer = valid.clone(); badPeer[start + 2] = 'x'; rejectDecode(badPeer);
        byte[] self = PeerBlockJournalSchema.encode(ORIGIN, "demo-peer-b",
                Collections.singletonList(pending("demo-peer-a")));
        int account = skipAscii(self, 16);
        self[account + 2 + "demo-peer-".length()] = 'a'; rejectDecode(self);
    }

    private static void statusRoundTripAndBounds() throws Exception {
        PeerBlockJournal.Status legacy = new PeerBlockJournal.Status("demo-peer-a", true, 0, "", true);
        PeerBlockJournal.Status tombstone = new PeerBlockJournal.Status("demo-peer-b", false, 2, "demo-unblock-b", false);
        List<PeerBlockJournal.Status> statuses = Arrays.asList(tombstone, legacy);
        byte[] bytes = encodeStatuses(statuses);
        PeerBlockJournalSchema.Record restored = PeerBlockJournalSchema.decode(bytes);
        check(restored.statuses.size() == 2, "Status count changed");
        sameStatus(legacy, restored.statuses.get(0)); sameStatus(tombstone, restored.statuses.get(1));
        equal(bytes, encodeStatuses(Arrays.asList(legacy, tombstone)));
        try { restored.statuses.clear(); fail("Mutable confirmed status snapshot"); }
        catch (UnsupportedOperationException expected) { assertions++; }
        check(tombstone.toString().equals("ConfirmedBlockStatus([redacted])"), "Status printed metadata");
        List<PeerBlockJournal.Status> maximum = new ArrayList<>();
        for (int i = 0; i < 1024; i++) maximum.add(new PeerBlockJournal.Status(
                String.format(java.util.Locale.ROOT, "demo-%04d", i) + "p".repeat(71),
                i % 2 == 0, Integer.MAX_VALUE, "demo-" + "m".repeat(75), false));
        List<PeerBlockJournal.Pending> pending = new ArrayList<>();
        for (int i = 0; i < 8; i++) pending.add(new PeerBlockJournal.Pending(
                "demo-" + i + "p".repeat(74), "demo-" + "m".repeat(75), Integer.MAX_VALUE, true));
        byte[] largest = PeerBlockJournalSchema.encode(ORIGIN, "demo-" + "a".repeat(75), pending, maximum);
        check(largest.length > 4096 && largest.length <= 256 * 1024, "Wrong full-capacity bound");
        PeerBlockJournalSchema.Record full = PeerBlockJournalSchema.decode(largest);
        check(full.statuses.size() == 1024 && full.pending.size() == 8, "Full journal lost entries");
        maximum.add(new PeerBlockJournal.Status("demo-one-too-many", true, 1, "demo-mutation", false));
        rejectSchema(() -> encodeStatuses(maximum));
        rejectSchema(() -> encodeStatuses(null));
        rejectSchema(() -> encodeStatuses(Arrays.asList((PeerBlockJournal.Status) null)));
        rejectSchema(() -> encodeStatuses(Arrays.asList(legacy, legacy)));
        rejectSchema(() -> encodeStatuses(Collections.singletonList(new PeerBlockJournal.Status(ACCOUNT, true, 1, "demo-self", false))));
        rejectSchema(() -> encodeStatuses(Collections.singletonList(new PeerBlockJournal.Status("demo-absent", false, 0, "", false))));
        for (int count : new int[] { -1, 1025, Integer.MAX_VALUE }) {
            byte[] invalid = bytes.clone(); ByteBuffer.wrap(invalid).putInt(12, count); rejectDecode(invalid);
        }
        rejectInput(() -> new PeerBlockJournal.Status("demo-peer", false, -1, "demo-mutation", false));
        rejectInput(() -> new PeerBlockJournal.Status("demo-peer", false, 1, "", false));
        rejectInput(() -> new PeerBlockJournal.Status("demo-peer", false, 1, null, false));
        rejectInput(() -> new PeerBlockJournal.Status("demo-peer", false, 1, "bad", false));
        rejectInput(() -> new PeerBlockJournal.Status("demo-peer", false, 1, "demo-mutation", true));
        rejectInput(() -> new PeerBlockJournal.Status("demo-peer", true, 0, "", false));
        rejectInput(() -> new PeerBlockJournal.Status("demo-peer", false, 0, "", true));
        rejectInput(() -> new PeerBlockJournal.Status("demo-peer", true, 0, "demo-mutation", true));
        rejectInput(() -> new PeerBlockJournal.Status("bad", false, 0, "", false));
    }

    private static void malformedStatuses() throws Exception {
        byte[] valid = encodeStatuses(Arrays.asList(
                new PeerBlockJournal.Status("demo-peer-a", true, 1, "demo-mutation-a", false),
                new PeerBlockJournal.Status("demo-peer-b", false, 2, "demo-mutation-b", false)));
        int first = entryStart(valid), mutation = skipAscii(valid, first), revision = skipAscii(valid, mutation);
        int length = revision + 6 - first;
        byte[] duplicate = valid.clone(); System.arraycopy(valid, first, duplicate, first + length, length); rejectDecode(duplicate);
        byte[] unordered = valid.clone();
        System.arraycopy(valid, first + length, unordered, first, length);
        System.arraycopy(valid, first, unordered, first + length, length); rejectDecode(unordered);
        for (int offset : new int[] { first, mutation }) {
            byte[] oversized = valid.clone(); ByteBuffer.wrap(oversized).putShort(offset, (short) 65535); rejectDecode(oversized);
            byte[] bad = valid.clone(); bad[offset + 2] = (byte) 255; rejectDecode(bad);
            bad = valid.clone(); bad[offset + 2] = 'x'; rejectDecode(bad);
        }
        for (int value : new int[] { Integer.MIN_VALUE, -1, 0 }) {
            byte[] invalid = valid.clone(); ByteBuffer.wrap(invalid).putInt(revision, value); rejectDecode(invalid);
        }
        for (int offset : new int[] { revision + 4, revision + 5 }) {
            for (int value : new int[] { 2, -1, 127 }) {
                byte[] invalid = valid.clone(); invalid[offset] = (byte) value; rejectDecode(invalid);
            }
        }
        byte[] invalidLegacy = valid.clone(); invalidLegacy[revision + 5] = 1; rejectDecode(invalidLegacy);
        for (int limit = 0; limit < valid.length; limit++) rejectDecode(Arrays.copyOf(valid, limit));
        rejectDecode(Arrays.copyOf(valid, valid.length + 1));
        byte[] absent = encodeStatuses(Collections.singletonList(new PeerBlockJournal.Status("demo-peer-a", true, 0, "", true)));
        int legacyRevision = skipAscii(absent, skipAscii(absent, entryStart(absent)));
        absent[legacyRevision + 4] = 0; absent[legacyRevision + 5] = 0; rejectDecode(absent);
    }

    private static byte[] encodeStatuses(List<PeerBlockJournal.Status> statuses) throws Exception {
        return PeerBlockJournalSchema.encode(ORIGIN, ACCOUNT, Collections.emptyList(), statuses);
    }

    private static void sameStatus(PeerBlockJournal.Status a, PeerBlockJournal.Status b) {
        check(a.peerId.equals(b.peerId) && a.blocked == b.blocked && a.revision == b.revision
                && a.mutationId.equals(b.mutationId) && a.legacyHold == b.legacyHold, "Status changed on round-trip");
    }

    private static void namespace() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest((ORIGIN + "\n" + ACCOUNT).getBytes(StandardCharsets.US_ASCII));
        StringBuilder expected = new StringBuilder("blocks-");
        for (byte b : hash) expected.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        String actual = PeerBlockJournal.namespace(ORIGIN, ACCOUNT);
        check(expected.toString().equals(actual) && actual.matches("blocks-[0-9a-f]{64}"), "Wrong namespace binding");
        check(!actual.equals(PeerBlockJournal.namespace(ORIGIN, "demo-account-b")), "Account namespaces collided");
        check(!actual.equals(PeerBlockJournal.namespace(ORIGIN.replace('a', 'b'), ACCOUNT)), "Origin namespaces collided");
    }

    private static PeerBlockJournal.Pending pending(String peer) { return new PeerBlockJournal.Pending(peer, "demo-operation-a", 7, true); }
    private static byte[] encode(List<PeerBlockJournal.Pending> entries) throws Exception { return PeerBlockJournalSchema.encode(ORIGIN, ACCOUNT, entries); }
    private static int skipAscii(byte[] bytes, int offset) { return offset + 2 + (ByteBuffer.wrap(bytes).getShort(offset) & 0xffff); }
    private static int entryStart(byte[] bytes) { return skipAscii(bytes, skipAscii(bytes, 16)); }
    private static int entryEnd(byte[] bytes, int start) { return skipAscii(bytes, skipAscii(bytes, start)) + 5; }
    private static void same(PeerBlockJournal.Pending a, PeerBlockJournal.Pending b) {
        check(a.peerId.equals(b.peerId) && a.mutationId.equals(b.mutationId)
                && a.expectedRevision == b.expectedRevision && a.blocked == b.blocked, "Request changed on round-trip");
    }
    private static void equal(byte[] a, byte[] b) { check(Arrays.equals(a, b), "Noncanonical schema bytes"); }
    private static void check(boolean value, String text) { if (!value) fail(text); assertions++; }
    private static void fail(String text) { throw new AssertionError(text); }
    private interface Attempt { void run() throws Exception; }
    private static void rejectInput(Attempt action) throws Exception {
        try { action.run(); fail("Invalid input accepted"); }
        catch (IllegalArgumentException error) { check("INVALID_BLOCK_JOURNAL_SCHEMA".equals(error.getMessage()), "Wrong input error"); }
    }
    private static void rejectEncode(String origin, String account, List<PeerBlockJournal.Pending> entries) throws Exception {
        rejectSchema(() -> PeerBlockJournalSchema.encode(origin, account, entries));
    }
    private static void rejectDecode(byte[] bytes) throws Exception { rejectSchema(() -> PeerBlockJournalSchema.decode(bytes)); }
    private static void rejectSchema(Attempt action) throws Exception {
        try { action.run(); fail("Invalid schema accepted"); }
        catch (IOException error) { check("INVALID_BLOCK_JOURNAL_SCHEMA".equals(error.getMessage()) && error.getCause() == null, "Wrong schema error"); }
    }
}
