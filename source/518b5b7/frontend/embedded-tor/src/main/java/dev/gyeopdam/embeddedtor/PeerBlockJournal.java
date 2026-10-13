package dev.gyeopdam.embeddedtor;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Encrypted pending block/unblock requests and confirmed revision floors, isolated by origin/account.
 * The caller must run every disk operation on AccountWorkQueue and wait for prepare()
 * to succeed before sending the exact Pending request. This class makes no network calls,
 * generates no operation IDs, and stores no credentials, auth tokens, or message history.
 */
public final class PeerBlockJournal {
    private final KeystoreBlobStore bytes;
    private final String origin;
    private final String account;

    public PeerBlockJournal(Context context, String origin, String accountId) {
        this.bytes = new KeystoreBlobStore(context, namespace(origin, accountId));
        this.origin = origin;
        this.account = accountId;
    }

    /** Read-only: absent state is empty, and no encryption key is generated. */
    public List<Pending> load() throws JournalException {
        byte[] encoded = null;
        try {
            encoded = read();
            return decodeBound(encoded).pending;
        } finally { wipe(encoded); }
    }

    /** Read-only immutable snapshot, including unblocked tombstones and migrated legacy holds. */
    public List<Status> loadStatuses() throws JournalException {
        byte[] encoded = null;
        try {
            encoded = read();
            return decodeBound(encoded).statuses;
        } finally { wipe(encoded); }
    }

    /** An exact retry is a no-op; conflicting requests for the same peer are never replaced. */
    public void prepare(Pending pending) throws JournalException {
        if (pending == null || account.equals(pending.peerId)) throw failure(Code.INVALID_INPUT);
        byte[] previous = null;
        try {
            previous = read();
            PeerBlockJournalSchema.Record record = decodeBound(previous);
            List<Pending> current = record.pending;
            for (Pending entry : current) {
                if (entry.peerId.equals(pending.peerId)) {
                    if (entry.sameRequest(pending)) return;
                    throw failure(Code.PENDING_CONFLICT);
                }
            }
            for (Status status : record.statuses) {
                if (status.peerId.equals(pending.peerId) && pending.expectedRevision < status.revision) throw failure(Code.STATE_CHANGED);
            }
            if (current.size() >= PeerBlockJournalSchema.MAX_PENDING) throw failure(Code.CAPACITY_REACHED);
            List<Pending> replacement = new ArrayList<>(current);
            replacement.add(pending);
            commit(previous, replacement, record.statuses);
        } finally { wipe(previous); }
    }

    /** Explicit local forget only: preserves all confirmed status/revision floors and the key. */
    public void acknowledge(String peerId, String mutationId) throws JournalException {
        if (!PeerBlockJournalSchema.validId(peerId) || !PeerBlockJournalSchema.validId(mutationId)
                || account.equals(peerId)) throw failure(Code.INVALID_INPUT);
        byte[] previous = null;
        try {
            previous = read();
            PeerBlockJournalSchema.Record record = decodeBound(previous);
            List<Pending> current = record.pending;
            List<Pending> replacement = new ArrayList<>(current);
            for (Pending entry : current) {
                if (!entry.peerId.equals(peerId)) continue;
                if (!entry.mutationId.equals(mutationId)) throw failure(Code.STALE_ACK);
                replacement.remove(entry);
                commit(previous, replacement, record.statuses);
                return;
            }
        } finally { wipe(previous); }
    }

    /**
     * Atomically stores monotonic actor-owned status and optionally acknowledges its exact request.
     * Empty acknowledgeMutationId means status-only. A completed exact acknowledgement is idempotent.
     */
    public void confirm(Status status, String acknowledgeMutationId) throws JournalException {
        if (status == null || account.equals(status.peerId) || acknowledgeMutationId == null
                || !acknowledgeMutationId.isEmpty() && !PeerBlockJournalSchema.validId(acknowledgeMutationId)) throw failure(Code.INVALID_INPUT);
        byte[] previous = null;
        try {
            previous = read();
            PeerBlockJournalSchema.Record record = decodeBound(previous);
            List<Status> statuses = new ArrayList<>(record.statuses);
            boolean changed = mergeStatus(statuses, status);
            List<Pending> pending = new ArrayList<>(record.pending);
            if (!acknowledgeMutationId.isEmpty()) {
                if (!acknowledgeMutationId.equals(status.mutationId) || status.revision == 0 || status.legacyHold) throw failure(Code.STALE_ACK);
                Pending matching = null;
                for (Pending entry : pending) if (entry.peerId.equals(status.peerId)) { matching = entry; break; }
                if (matching == null) {
                    // A replay may arrive after the successful atomic status+ack commit.
                    if (changed) throw failure(Code.STALE_ACK);
                } else {
                    if (!acknowledgeMutationId.equals(matching.mutationId) || matching.blocked != status.blocked
                            || (long) matching.expectedRevision + 1 != status.revision) throw failure(Code.STALE_ACK);
                    pending.remove(matching);
                    changed = true;
                }
            }
            if (changed) commit(previous, pending, statuses);
        } finally { wipe(previous); }
    }

    /** Applies one validated server page (at most 64 distinct peers) in a single CAS commit. */
    public void confirmAll(List<Status> page) throws JournalException {
        if (page == null || page.size() > 64) throw failure(Code.INVALID_INPUT);
        List<Status> snapshot = new ArrayList<>(page);
        Set<String> peers = new HashSet<>();
        for (Status status : snapshot) {
            if (status == null || account.equals(status.peerId) || !peers.add(status.peerId)) throw failure(Code.INVALID_INPUT);
        }
        byte[] previous = null;
        try {
            previous = read();
            PeerBlockJournalSchema.Record record = decodeBound(previous);
            List<Status> statuses = new ArrayList<>(record.statuses);
            boolean changed = false;
            for (Status status : snapshot) changed |= mergeStatus(statuses, status);
            if (changed) commit(previous, record.pending, statuses);
        } finally { wipe(previous); }
    }

    private static boolean mergeStatus(List<Status> statuses, Status status) throws JournalException {
        for (int i = 0; i < statuses.size(); i++) {
            Status previous = statuses.get(i);
            if (!previous.peerId.equals(status.peerId)) continue;
            if (status.revision < previous.revision
                    || status.revision == previous.revision && !status.sameStatus(previous)) throw failure(Code.STATE_CHANGED);
            if (status.sameStatus(previous)) return false;
            statuses.set(i, status);
            return true;
        }
        // Rev0 absence is not a durable decision and never creates a tombstone or key.
        if (status.absent()) return false;
        if (statuses.size() >= PeerBlockJournalSchema.MAX_STATUSES) throw failure(Code.CAPACITY_REACHED);
        statuses.add(status);
        return true;
    }

    /**
     * Recovery only after a separate explicit user confirmation. May lose unconfirmed local
     * requests whose server outcome is unknown, plus confirmed revision floors. Clears only this journal namespace; it cannot
     * undo server blocks and never touches the separate session, Signal, or message stores.
     */
    public void resetAfterConfirmation() throws JournalException {
        try { bytes.clear(); }
        catch (KeystoreBlobStore.StorageException error) { throw storageFailure(error); }
    }

    private byte[] read() throws JournalException {
        try { return bytes.read(); }
        catch (KeystoreBlobStore.StorageException error) { throw storageFailure(error); }
    }

    private PeerBlockJournalSchema.Record decodeBound(byte[] encoded) throws JournalException {
        if (encoded == null) return new PeerBlockJournalSchema.Record(origin, account, Collections.emptyList(), Collections.emptyList());
        PeerBlockJournalSchema.Record record;
        try { record = PeerBlockJournalSchema.decode(encoded); }
        catch (IOException ignored) { throw failure(Code.CORRUPT_STATE); }
        if (!origin.equals(record.origin)) throw failure(Code.ORIGIN_MISMATCH);
        if (!account.equals(record.account)) throw failure(Code.IDENTITY_MISMATCH);
        return record;
    }

    private void commit(byte[] expected, List<Pending> replacement, List<Status> statuses) throws JournalException {
        byte[] encoded = null;
        // KeystoreBlobStore's validator runs under its process + OS file locks. Retain the
        // journal-specific rejection separately because its storage error enum is narrower.
        final Code[] rejection = { null };
        try {
            encoded = PeerBlockJournalSchema.encode(origin, account, replacement, statuses);
            bytes.write(encoded, previous -> {
                try { decodeBound(previous); }
                catch (JournalException error) {
                    rejection[0] = error.code;
                    throw new KeystoreBlobStore.StorageException(KeystoreBlobStore.Code.CORRUPT_STATE);
                }
                if (!Arrays.equals(expected, previous)) {
                    rejection[0] = Code.STATE_CHANGED;
                    throw new KeystoreBlobStore.StorageException(KeystoreBlobStore.Code.CORRUPT_STATE);
                }
            });
        } catch (KeystoreBlobStore.StorageException error) {
            if (rejection[0] != null) throw failure(rejection[0]);
            throw storageFailure(error);
        } catch (IOException ignored) { throw failure(Code.INVALID_INPUT); }
        finally { wipe(encoded); }
    }

    static String namespace(String origin, String account) {
        PeerBlockJournalSchema.validateBinding(origin, account);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((origin + "\n" + account).getBytes(StandardCharsets.US_ASCII));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] value = new char[64];
            for (int i = 0; i < digest.length; i++) {
                value[i * 2] = hex[(digest[i] & 0xff) >>> 4];
                value[i * 2 + 1] = hex[digest[i] & 15];
            }
            return "blocks-" + new String(value);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    public static final class Pending {
        public final String peerId;
        public final String mutationId;
        public final int expectedRevision;
        public final boolean blocked;

        public Pending(String peerId, String mutationId, int expectedRevision, boolean blocked) {
            PeerBlockJournalSchema.validatePending(peerId, mutationId, expectedRevision);
            this.peerId = peerId;
            this.mutationId = mutationId;
            this.expectedRevision = expectedRevision;
            this.blocked = blocked;
        }

        private boolean sameRequest(Pending other) {
            return peerId.equals(other.peerId) && mutationId.equals(other.mutationId)
                    && expectedRevision == other.expectedRevision && blocked == other.blocked;
        }

        @Override public String toString() { return "PendingBlockRequest([redacted])"; }
    }

    public static final class Status {
        public final String peerId;
        public final boolean blocked;
        public final int revision;
        public final String mutationId;
        public final boolean legacyHold;

        public Status(String peerId, boolean blocked, int revision, String mutationId, boolean legacyHold) {
            PeerBlockJournalSchema.validateStatus(peerId, blocked, revision, mutationId, legacyHold);
            this.peerId = peerId;
            this.blocked = blocked;
            this.revision = revision;
            this.mutationId = mutationId;
            this.legacyHold = legacyHold;
        }

        boolean absent() { return revision == 0 && !blocked && !legacyHold && mutationId.isEmpty(); }
        private boolean sameStatus(Status other) {
            return peerId.equals(other.peerId) && blocked == other.blocked && revision == other.revision
                    && mutationId.equals(other.mutationId) && legacyHold == other.legacyHold;
        }
        @Override public String toString() { return "ConfirmedBlockStatus([redacted])"; }
    }

    public enum Code {
        INVALID_INPUT, CORRUPT_STATE, KEY_UNAVAILABLE, IO_FAILED, ORIGIN_MISMATCH,
        IDENTITY_MISMATCH, PENDING_CONFLICT, CAPACITY_REACHED, STALE_ACK, STATE_CHANGED
    }

    public static final class JournalException extends IOException {
        private static final long serialVersionUID = 1L;
        public final Code code;
        private JournalException(Code code) { super(code.name()); this.code = code; }
    }

    private static JournalException storageFailure(KeystoreBlobStore.StorageException error) {
        return failure(Code.valueOf(error.code.name()));
    }
    private static JournalException failure(Code code) { return new JournalException(code); }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
    @Override public String toString() { return "PeerBlockJournal([redacted])"; }
}
