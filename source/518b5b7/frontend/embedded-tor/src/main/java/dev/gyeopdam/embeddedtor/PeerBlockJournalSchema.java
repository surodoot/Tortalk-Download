package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded, canonical ASCII binary schema. Contains request metadata, never credentials. */
final class PeerBlockJournalSchema {
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_PENDING = 8;
    static final int MAX_STATUSES = 1024;
    private static final int MAGIC = 0x54544231; // TTB1
    private static final int VERSION = 1;
    private static final Pattern ID = Pattern.compile("demo-[a-z0-9-]{1,75}");

    private PeerBlockJournalSchema() { }

    static boolean validId(String value) {
        return value != null && value.length() >= 6 && value.length() <= 80
                && ID.matcher(value).matches();
    }

    static void validateBinding(String origin, String account) {
        if (!SessionSchema.isCanonicalOrigin(origin) || !validId(account)) invalid();
    }

    static void validatePending(String peer, String mutation, int revision) {
        if (!validId(peer) || !validId(mutation) || revision < 0) invalid();
    }

    static void validateStatus(String peer, boolean blocked, int revision, String mutation, boolean legacyHold) {
        if (!validId(peer) || revision < 0 || mutation == null) invalid();
        if (revision == 0) {
            if (!mutation.isEmpty() || blocked != legacyHold) invalid();
        } else if (!validId(mutation) || legacyHold) invalid();
    }

    static byte[] encode(String origin, String account, List<PeerBlockJournal.Pending> pending)
            throws IOException {
        return encode(origin, account, pending, Collections.emptyList());
    }

    static byte[] encode(String origin, String account, List<PeerBlockJournal.Pending> pending,
                         List<PeerBlockJournal.Status> statuses) throws IOException {
        try { validateBinding(origin, account); }
        catch (IllegalArgumentException ignored) { throw failure(); }
        if (pending == null || pending.size() > MAX_PENDING || statuses == null || statuses.size() > MAX_STATUSES) throw failure();
        List<PeerBlockJournal.Pending> ordered = new ArrayList<>(pending);
        for (PeerBlockJournal.Pending entry : ordered) {
            if (entry == null || account.equals(entry.peerId)) throw failure();
            try { validatePending(entry.peerId, entry.mutationId, entry.expectedRevision); }
            catch (IllegalArgumentException ignored) { throw failure(); }
        }
        Collections.sort(ordered, Comparator.comparing(entry -> entry.peerId));
        List<PeerBlockJournal.Status> orderedStatuses = new ArrayList<>(statuses);
        for (PeerBlockJournal.Status status : orderedStatuses) {
            if (status == null || account.equals(status.peerId) || status.absent()) throw failure();
            try { validateStatus(status.peerId, status.blocked, status.revision, status.mutationId, status.legacyHold); }
            catch (IllegalArgumentException ignored) { throw failure(); }
        }
        Collections.sort(orderedStatuses, Comparator.comparing(status -> status.peerId));
        int size = 16 + 4 + origin.length() + account.length();
        String previous = null;
        for (PeerBlockJournal.Pending entry : ordered) {
            if (entry.peerId.equals(previous)) throw failure();
            previous = entry.peerId;
            size += 9 + entry.peerId.length() + entry.mutationId.length();
        }
        previous = null;
        for (PeerBlockJournal.Status status : orderedStatuses) {
            if (status.peerId.equals(previous)) throw failure();
            previous = status.peerId;
            size += 10 + status.peerId.length() + status.mutationId.length();
        }
        if (size > MAX_BYTES) throw failure();
        ByteBuffer output = ByteBuffer.allocate(size).putInt(MAGIC).putInt(VERSION)
                .putInt(ordered.size()).putInt(orderedStatuses.size());
        putAscii(output, origin);
        putAscii(output, account);
        for (PeerBlockJournal.Pending entry : ordered) {
            putAscii(output, entry.peerId);
            putAscii(output, entry.mutationId);
            output.putInt(entry.expectedRevision).put((byte) (entry.blocked ? 1 : 0));
        }
        for (PeerBlockJournal.Status status : orderedStatuses) {
            putAscii(output, status.peerId);
            putAscii(output, status.mutationId);
            output.putInt(status.revision).put((byte) (status.blocked ? 1 : 0)).put((byte) (status.legacyHold ? 1 : 0));
        }
        return output.array();
    }

    static Record decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 20 || bytes.length > MAX_BYTES) throw failure();
        ByteBuffer input = ByteBuffer.wrap(bytes);
        if (input.getInt() != MAGIC || input.getInt() != VERSION) throw failure();
        int count = input.getInt();
        int statusCount = input.getInt();
        if (count < 0 || count > MAX_PENDING || statusCount < 0 || statusCount > MAX_STATUSES) throw failure();
        String origin = readAscii(input, 69, false);
        String account = readAscii(input, 80, false);
        try { validateBinding(origin, account); }
        catch (IllegalArgumentException ignored) { throw failure(); }
        List<PeerBlockJournal.Pending> pending = new ArrayList<>(count);
        String previous = null;
        for (int i = 0; i < count; i++) {
            String peer = readAscii(input, 80, false);
            String mutation = readAscii(input, 80, false);
            if (input.remaining() < 5) throw failure();
            int revision = input.getInt();
            byte blocked = input.get();
            if (blocked != 0 && blocked != 1 || account.equals(peer)
                    || previous != null && previous.compareTo(peer) >= 0) throw failure();
            try { pending.add(new PeerBlockJournal.Pending(peer, mutation, revision, blocked == 1)); }
            catch (IllegalArgumentException ignored) { throw failure(); }
            previous = peer;
        }
        List<PeerBlockJournal.Status> statuses = new ArrayList<>(statusCount);
        previous = null;
        for (int i = 0; i < statusCount; i++) {
            String peer = readAscii(input, 80, false);
            String mutation = readAscii(input, 80, true);
            if (input.remaining() < 6) throw failure();
            int revision = input.getInt();
            byte blocked = input.get(), legacy = input.get();
            if ((blocked != 0 && blocked != 1) || (legacy != 0 && legacy != 1) || account.equals(peer)
                    || previous != null && previous.compareTo(peer) >= 0) throw failure();
            try {
                PeerBlockJournal.Status status = new PeerBlockJournal.Status(peer, blocked == 1, revision, mutation, legacy == 1);
                if (status.absent()) throw failure();
                statuses.add(status);
            } catch (IllegalArgumentException ignored) { throw failure(); }
            previous = peer;
        }
        if (input.hasRemaining()) throw failure();
        return new Record(origin, account, pending, statuses);
    }

    private static void putAscii(ByteBuffer output, String value) {
        output.putShort((short) value.length());
        for (int i = 0; i < value.length(); i++) output.put((byte) value.charAt(i));
    }

    private static String readAscii(ByteBuffer input, int maximum, boolean allowEmpty) throws IOException {
        if (input.remaining() < 2) throw failure();
        int length = input.getShort() & 0xffff;
        if (!allowEmpty && length == 0 || length > maximum || length > input.remaining()) throw failure();
        char[] value = new char[length];
        for (int i = 0; i < length; i++) {
            int c = input.get() & 0xff;
            if (c < 33 || c > 126) throw failure();
            value[i] = (char) c;
        }
        return new String(value);
    }

    static final class Record {
        final String origin, account;
        final List<PeerBlockJournal.Pending> pending;
        final List<PeerBlockJournal.Status> statuses;
        Record(String origin, String account, List<PeerBlockJournal.Pending> pending, List<PeerBlockJournal.Status> statuses) {
            this.origin = origin;
            this.account = account;
            this.pending = Collections.unmodifiableList(new ArrayList<>(pending));
            this.statuses = Collections.unmodifiableList(new ArrayList<>(statuses));
        }
    }

    private static void invalid() { throw new IllegalArgumentException("INVALID_BLOCK_JOURNAL_SCHEMA"); }
    private static IOException failure() { return new IOException("INVALID_BLOCK_JOURNAL_SCHEMA"); }
}
