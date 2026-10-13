package dev.gyeopdam.embeddedtor;

import android.content.Context;
import android.system.Os;
import android.util.AtomicFile;
import dev.gyeopdam.embeddedtor.host.FakeAndroidKeyStoreProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Actual production journal/envelope/storage with explicitly synthetic Android host fixtures. */
public final class PeerBlockJournalHostReview {
    private static final String ORIGIN = "http://" + "a".repeat(56) + ".onion";
    private static final String OTHER_ORIGIN = "http://" + "b".repeat(56) + ".onion";
    private static final String ACCOUNT = "demo-account-a";
    private static final PeerBlockJournal.Pending FIRST = pending("demo-peer-a", "demo-mutation-a", 0, true);
    private static final PeerBlockJournal.Pending OTHER = pending("demo-peer-b", "demo-mutation-b", 4, false);
    private static int scenarios;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected private host-test work directory");
        if (Security.getProvider("AndroidKeyStore") != null) throw new AssertionError("Refusing to replace an existing provider");
        FakeAndroidKeyStoreProvider provider = new FakeAndroidKeyStoreProvider();
        Security.addProvider(provider);
        try {
            Path root = Path.of(args[0]);
            run(root, "absent loads and acknowledgements create no key or journal", PeerBlockJournalHostReview::absent);
            run(root, "exact retry survives recreation and preserves ciphertext", PeerBlockJournalHostReview::retry);
            run(root, "changed mutation fields cannot replace pending requests", PeerBlockJournalHostReview::conflicts);
            run(root, "bounded capacity preserves all eight pending peers", PeerBlockJournalHostReview::capacity);
            run(root, "acknowledgement preserves other peers and stale ack rejects", PeerBlockJournalHostReview::acknowledge);
            run(root, "last acknowledgement writes an empty journal and keeps the key", PeerBlockJournalHostReview::emptyJournal);
            run(root, "prepare CAS rejects a concurrent peer addition", PeerBlockJournalHostReview::prepareRace);
            run(root, "prepare CAS distinguishes absence from an empty journal", PeerBlockJournalHostReview::absenceRace);
            run(root, "acknowledgement CAS preserves concurrent peer additions", PeerBlockJournalHostReview::acknowledgeRace);
            run(root, "stale acknowledgement CAS cannot erase a newer peer request", PeerBlockJournalHostReview::staleAcknowledgementRace);
            run(root, "commit CAS validates a raced foreign binding", PeerBlockJournalHostReview::bindingRace);
            run(root, "commit CAS cannot overwrite raced schema corruption", PeerBlockJournalHostReview::corruptionRace);
            run(root, "uncertain prepare preserves the exact durable retry", PeerBlockJournalHostReview::uncertainPrepare);
            run(root, "uncertain acknowledgement preserves its committed result", PeerBlockJournalHostReview::uncertainAcknowledgement);
            run(root, "read durability failure blocks release without overwriting", PeerBlockJournalHostReview::readBarrier);
            run(root, "corrupt ciphertext fails closed for all ordinary operations", PeerBlockJournalHostReview::corruptCiphertext);
            run(root, "invalid schema fails closed for all ordinary operations", PeerBlockJournalHostReview::corruptSchema);
            run(root, "missing key cannot regenerate over existing journal", PeerBlockJournalHostReview::missingKey);
            run(root, "origin and account schema bindings are checked", PeerBlockJournalHostReview::binding);
            run(root, "account and origin namespaces isolate AAD and pending state", PeerBlockJournalHostReview::isolation);
            run(root, "explicit reset is scoped to the block journal only", PeerBlockJournalHostReview::reset);
            run(root, "invalid inputs and namespace bounds generate no keys", PeerBlockJournalHostReview::invalidInput);
            run(root, "revision-zero absence creates no status row or key", PeerBlockJournalHostReview::absentStatus);
            run(root, "confirmed tombstones and revision floors survive recreation", PeerBlockJournalHostReview::statusRecreation);
            run(root, "legacy hold cannot regress to unconfirmed absence", PeerBlockJournalHostReview::legacyStatus);
            run(root, "confirmed mutation and exact pending acknowledgement are atomic", PeerBlockJournalHostReview::atomicConfirm);
            run(root, "confirmation rejects mismatched acknowledgements without partial status", PeerBlockJournalHostReview::invalidConfirmation);
            run(root, "exact confirmed replay cannot remove a newer pending request", PeerBlockJournalHostReview::confirmedReplay);
            run(root, "one server page commits once and exact repeat preserves ciphertext", PeerBlockJournalHostReview::statusPage);
            run(root, "status page regression rejects the entire page", PeerBlockJournalHostReview::pageRegression);
            run(root, "status bounds retain 1024 tombstones plus eight pending requests", PeerBlockJournalHostReview::statusCapacity);
            run(root, "confirmation CAS cannot rewind a newer revision", PeerBlockJournalHostReview::confirmRace);
            run(root, "status page CAS preserves a concurrent pending request", PeerBlockJournalHostReview::pageRace);
            run(root, "uncertain confirmation retains status and acknowledgement together", PeerBlockJournalHostReview::uncertainConfirmation);
            run(root, "explicit local forget preserves confirmed revision floors", PeerBlockJournalHostReview::forgetPreservesStatus);
            System.out.println("PASS: " + scenarios + " production block-journal host regression scenarios");
            System.out.println("DOUBLES: Context, AtomicFile, Os, AndroidKeyStore; synthetic memory-only keys.");
            System.out.println("NOT VERIFIED: real Android/Keystore, power loss, UI queueing, HTTP retries, or process-death recovery.");
        } finally {
            FakeAndroidKeyStoreProvider.reset();
            if (Security.getProvider("AndroidKeyStore") == provider) Security.removeProvider("AndroidKeyStore");
        }
    }

    private static void absent(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context);
        check(journal.load().isEmpty(), "Absent journal was not empty");
        journal.acknowledge(FIRST.peerId, FIRST.mutationId);
        check(journal.load().isEmpty() && !Files.exists(path(context)), "Absent acknowledgement created a journal");
        check(FakeAndroidKeyStoreProvider.generations == 0 && FakeAndroidKeyStoreProvider.keyCount() == 0
                && AtomicFile.completedCommits == 0, "Read-only operations generated a key or committed state");
    }

    private static void retry(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST);
        byte[] committed = ciphertext(context);
        same(FIRST, journal(context).load().get(0));
        journal(context).prepare(pending(FIRST.peerId, FIRST.mutationId, FIRST.expectedRevision, FIRST.blocked));
        equal(committed, ciphertext(context));
        check(AtomicFile.completedCommits == 1 && FakeAndroidKeyStoreProvider.generations == 1, "Exact retry rewrote state");
        String disk = new String(committed, StandardCharsets.ISO_8859_1);
        check(!disk.contains(FIRST.peerId) && !disk.contains(FIRST.mutationId) && !disk.contains(ORIGIN), "Request metadata was stored in plaintext");
        check(journal.toString().equals("PeerBlockJournal([redacted])"), "Journal printed binding");
        try { journal.load().clear(); fail("Mutable load snapshot"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static void conflicts(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST);
        byte[] committed = ciphertext(context);
        for (PeerBlockJournal.Pending changed : new PeerBlockJournal.Pending[] {
                pending(FIRST.peerId, "demo-new-id", 0, true),
                pending(FIRST.peerId, FIRST.mutationId, 1, true),
                pending(FIRST.peerId, FIRST.mutationId, 0, false) }) {
            rejected(() -> journal.prepare(changed), PeerBlockJournal.Code.PENDING_CONFLICT);
            equal(committed, ciphertext(context));
        }
        check(AtomicFile.completedCommits == 1, "Conflict committed replacement");
    }

    private static void capacity(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context);
        for (int i = 0; i < 8; i++) journal.prepare(pending("demo-peer-" + i, "demo-mutation-" + i, i, i % 2 == 0));
        byte[] committed = ciphertext(context);
        rejected(() -> journal.prepare(FIRST), PeerBlockJournal.Code.CAPACITY_REACHED);
        equal(committed, ciphertext(context));
        journal.prepare(pending("demo-peer-0", "demo-mutation-0", 0, true));
        equal(committed, ciphertext(context));
        check(journal.load().size() == 8 && committed.length < 4096, "Capacity did not preserve all peers");
    }

    private static void acknowledge(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST); journal.prepare(OTHER);
        List<PeerBlockJournal.Pending> before = journal.load();
        byte[] committed = ciphertext(context);
        rejected(() -> journal.acknowledge(FIRST.peerId, "demo-stale-id"), PeerBlockJournal.Code.STALE_ACK);
        equal(committed, ciphertext(context));
        journal.acknowledge("demo-absent-peer", "demo-absent-mutation");
        equal(committed, ciphertext(context));
        journal.acknowledge(FIRST.peerId, FIRST.mutationId);
        List<PeerBlockJournal.Pending> after = journal(context).load();
        check(before.size() == 2 && after.size() == 1, "Acknowledgement mutated a snapshot or removed another peer");
        same(OTHER, after.get(0));
        byte[] acknowledged = ciphertext(context);
        journal.acknowledge(FIRST.peerId, FIRST.mutationId);
        equal(acknowledged, ciphertext(context));
        journal.prepare(pending(FIRST.peerId, "demo-new-mutation", 1, false));
        rejected(() -> journal.acknowledge(FIRST.peerId, FIRST.mutationId), PeerBlockJournal.Code.STALE_ACK);
        check(journal.load().size() == 2, "Stale acknowledgement removed a newer request");
    }

    private static void emptyJournal(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST);
        journal.acknowledge(FIRST.peerId, FIRST.mutationId);
        check(Files.exists(path(context)) && journal(context).load().isEmpty(), "Last acknowledgement deleted the journal");
        check(FakeAndroidKeyStoreProvider.keyCount() == 1 && FakeAndroidKeyStoreProvider.deletions == 0, "Acknowledgement deleted its key");
        byte[] plain = raw(context).read();
        check(plain != null && PeerBlockJournalSchema.decode(plain).pending.isEmpty(), "Empty journal lost binding");
        journal.prepare(OTHER);
        check(FakeAndroidKeyStoreProvider.generations == 1, "Empty journal rotated the encryption key");
    }

    private static void prepareRace(TestContext context) throws Exception {
        context.raceBeforeCommit(() -> journal(context).prepare(OTHER));
        rejected(() -> journal(context).prepare(FIRST), PeerBlockJournal.Code.STATE_CHANGED);
        same(OTHER, journal(context).load().get(0));
        check(AtomicFile.completedCommits == 1, "Failed CAS wrote a journal");
        journal(context).prepare(FIRST);
        check(journal(context).load().size() == 2, "Retry did not preserve the other peer");
    }

    private static void absenceRace(TestContext context) throws Exception {
        context.raceBeforeCommit(() -> { PeerBlockJournal other = journal(context); other.prepare(OTHER); other.acknowledge(OTHER.peerId, OTHER.mutationId); });
        rejected(() -> journal(context).prepare(FIRST), PeerBlockJournal.Code.STATE_CHANGED);
        check(journal(context).load().isEmpty() && AtomicFile.completedCommits == 2, "CAS equated null with an empty encoded journal");
    }

    private static void acknowledgeRace(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST);
        context.raceBeforeCommit(() -> journal(context).prepare(OTHER));
        rejected(() -> journal.acknowledge(FIRST.peerId, FIRST.mutationId), PeerBlockJournal.Code.STATE_CHANGED);
        check(journal.load().size() == 2, "Acknowledgement erased concurrent addition");
        journal.acknowledge(FIRST.peerId, FIRST.mutationId);
        same(OTHER, journal.load().get(0));
    }

    private static void staleAcknowledgementRace(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST);
        PeerBlockJournal.Pending newer = pending(FIRST.peerId, "demo-new-mutation", 1, false);
        context.raceBeforeCommit(() -> { PeerBlockJournal competing = journal(context); competing.acknowledge(FIRST.peerId, FIRST.mutationId); competing.prepare(newer); });
        rejected(() -> journal.acknowledge(FIRST.peerId, FIRST.mutationId), PeerBlockJournal.Code.STATE_CHANGED);
        same(newer, journal.load().get(0));
    }

    private static void bindingRace(TestContext context) throws Exception {
        context.raceBeforeCommit(() -> raw(context).write(PeerBlockJournalSchema.encode(ORIGIN, "demo-other-account", Collections.emptyList())));
        rejected(() -> journal(context).prepare(FIRST), PeerBlockJournal.Code.IDENTITY_MISMATCH);
        check(AtomicFile.completedCommits == 1, "Raced foreign binding was overwritten");
        ordinaryOperationsFail(context, PeerBlockJournal.Code.IDENTITY_MISMATCH);
    }

    private static void corruptionRace(TestContext context) throws Exception {
        context.raceBeforeCommit(() -> raw(context).write(new byte[] { 1, 2, 3 }));
        rejected(() -> journal(context).prepare(FIRST), PeerBlockJournal.Code.CORRUPT_STATE);
        check(AtomicFile.completedCommits == 1, "Raced corrupt schema was overwritten");
        ordinaryOperationsFail(context, PeerBlockJournal.Code.CORRUPT_STATE);
    }

    private static void uncertainPrepare(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context);
        Os.inject(Os.Fault.FSYNC, 1);
        rejected(() -> journal.prepare(FIRST), PeerBlockJournal.Code.IO_FAILED);
        check(AtomicFile.completedCommits == 1, "Fault preceded durable replacement");
        byte[] committed = ciphertext(context);
        ordinaryOperationsFail(context, PeerBlockJournal.Code.IO_FAILED);
        Os.recover(); same(FIRST, journal(context).load().get(0));
        journal(context).prepare(FIRST);
        equal(committed, ciphertext(context));
        check(AtomicFile.completedCommits == 1, "Uncertain retry rewrote the exact request");
    }

    private static void uncertainAcknowledgement(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST); journal.prepare(OTHER);
        Os.inject(Os.Fault.FSYNC, 3);
        rejected(() -> journal.acknowledge(FIRST.peerId, FIRST.mutationId), PeerBlockJournal.Code.IO_FAILED);
        byte[] committed = ciphertext(context);
        ordinaryOperationsFail(context, PeerBlockJournal.Code.IO_FAILED);
        Os.recover(); same(OTHER, journal(context).load().get(0));
        journal.acknowledge(FIRST.peerId, FIRST.mutationId);
        equal(committed, ciphertext(context));
        check(AtomicFile.completedCommits == 3 && FakeAndroidKeyStoreProvider.deletions == 0, "Uncertain acknowledgement reset state");
    }

    private static void readBarrier(TestContext context) throws Exception {
        journal(context).prepare(FIRST);
        AtomicFile.closeReadStreamAtEof = true;
        ordinaryOperationsFail(context, PeerBlockJournal.Code.IO_FAILED);
        AtomicFile.closeReadStreamAtEof = false;
        same(FIRST, journal(context).load().get(0));
    }

    private static void corruptCiphertext(TestContext context) throws Exception {
        journal(context).prepare(FIRST);
        byte[] corrupt = ciphertext(context); corrupt[corrupt.length - 1] ^= 1;
        Files.write(path(context), corrupt);
        ordinaryOperationsFail(context, PeerBlockJournal.Code.CORRUPT_STATE);
    }

    private static void corruptSchema(TestContext context) throws Exception {
        raw(context).write(new byte[] { 1, 2, 3 });
        ordinaryOperationsFail(context, PeerBlockJournal.Code.CORRUPT_STATE);
    }

    private static void missingKey(TestContext context) throws Exception {
        journal(context).prepare(FIRST); FakeAndroidKeyStoreProvider.loseKeys();
        ordinaryOperationsFail(context, PeerBlockJournal.Code.KEY_UNAVAILABLE);
        check(FakeAndroidKeyStoreProvider.generations == 1 && FakeAndroidKeyStoreProvider.keyCount() == 0, "Missing key regenerated over ciphertext");
    }

    private static void binding(TestContext context) throws Exception {
        raw(context).write(PeerBlockJournalSchema.encode(OTHER_ORIGIN, ACCOUNT, Collections.emptyList()));
        ordinaryOperationsFail(context, PeerBlockJournal.Code.ORIGIN_MISMATCH);
        raw(context).write(PeerBlockJournalSchema.encode(ORIGIN, "demo-other-account", Collections.emptyList()));
        ordinaryOperationsFail(context, PeerBlockJournal.Code.IDENTITY_MISMATCH);
    }

    private static void isolation(TestContext context) throws Exception {
        PeerBlockJournal first = journal(context);
        PeerBlockJournal second = new PeerBlockJournal(context, ORIGIN, "demo-account-b");
        PeerBlockJournal third = new PeerBlockJournal(context, OTHER_ORIGIN, ACCOUNT);
        first.prepare(FIRST); second.prepare(OTHER); third.prepare(OTHER);
        same(FIRST, first.load().get(0)); same(OTHER, second.load().get(0)); same(OTHER, third.load().get(0));
        check(FakeAndroidKeyStoreProvider.keyCount() == 3, "Journal identities shared an alias");
        Path secondPath = path(context, ORIGIN, "demo-account-b");
        byte[] secondBytes = Files.readAllBytes(secondPath);
        Files.write(path(context), secondBytes);
        ordinaryOperationsFail(context, PeerBlockJournal.Code.CORRUPT_STATE);
        same(OTHER, second.load().get(0)); same(OTHER, third.load().get(0));
    }

    private static void reset(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST);
        journal.confirm(status(OTHER.peerId, false, 5, OTHER.mutationId), "");
        PeerBlockJournal other = new PeerBlockJournal(context, ORIGIN, "demo-account-b"); other.prepare(OTHER);
        KeystoreBlobStore session = new KeystoreBlobStore(context, "session");
        KeystoreBlobStore history = new KeystoreBlobStore(context, "signal-" + "a".repeat(64));
        byte[] sentinel = "SYNTHETIC_UNRELATED_HISTORY_SENTINEL".getBytes(StandardCharsets.US_ASCII);
        session.write(sentinel); history.write(sentinel);
        byte[] corrupt = ciphertext(context); corrupt[corrupt.length - 1] ^= 1; Files.write(path(context), corrupt);
        journal.resetAfterConfirmation();
        check(!Files.exists(path(context)) && journal.load().isEmpty() && journal.loadStatuses().isEmpty(), "Explicit journal reset failed");
        check(FakeAndroidKeyStoreProvider.deletions == 1 && FakeAndroidKeyStoreProvider.keyCount() == 3, "Reset cleared unrelated keys");
        same(OTHER, other.load().get(0)); equal(sentinel, session.read()); equal(sentinel, history.read());
        journal.prepare(FIRST); same(FIRST, journal.load().get(0));
    }

    private static void invalidInput(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context);
        rejected(() -> journal.prepare(null), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.prepare(pending(ACCOUNT, "demo-mutation", 0, true)), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.acknowledge(ACCOUNT, FIRST.mutationId), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.acknowledge("bad", FIRST.mutationId), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.acknowledge(FIRST.peerId, "bad"), PeerBlockJournal.Code.INVALID_INPUT);
        try { new PeerBlockJournal(context, ORIGIN + "/", ACCOUNT); fail("Noncanonical origin accepted"); }
        catch (IllegalArgumentException expected) { }
        for (String namespace : new String[] { "blocks", "blocks-", "blocks-" + "a".repeat(63),
                "blocks-" + "a".repeat(65), "blocks-" + "A".repeat(64), "blocks-" + "z".repeat(64), "blocks-../session" }) {
            try { new KeystoreBlobStore(context, namespace); fail("Invalid blocks namespace accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { raw(context).write(new byte[256 * 1024 + 1]); fail("Blocks namespace exceeded 256 KiB"); }
        catch (KeystoreBlobStore.StorageException error) { check(error.code == KeystoreBlobStore.Code.INVALID_INPUT, "Wrong size error"); }
        for (String namespace : new String[] { "session", "config", "enrollment" }) {
            try { new KeystoreBlobStore(context, namespace).write(new byte[4097]); fail("Ordinary namespace exceeded 4 KiB"); }
            catch (KeystoreBlobStore.StorageException error) { check(error.code == KeystoreBlobStore.Code.INVALID_INPUT, "Wrong ordinary size error"); }
        }
        check(FakeAndroidKeyStoreProvider.generations == 0 && AtomicFile.completedCommits == 0, "Invalid input generated keys or state");
    }

    private static void absentStatus(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context);
        check(journal.loadStatuses().isEmpty(), "Absent journal had confirmed statuses");
        PeerBlockJournal.Status absent = new PeerBlockJournal.Status(FIRST.peerId, false, 0, "", false);
        journal.confirm(absent, ""); journal.confirmAll(Arrays.asList(absent)); journal.confirmAll(Collections.emptyList());
        check(journal.loadStatuses().isEmpty() && !Files.exists(path(context)), "Absent status created a stored row");
        check(FakeAndroidKeyStoreProvider.generations == 0 && AtomicFile.completedCommits == 0, "Absent status generated a key");
        rejected(() -> journal.confirm(null, ""), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.confirm(absent, null), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.confirm(absent, "bad"), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.confirm(status(ACCOUNT, true, 1, "demo-self"), ""), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.confirmAll(null), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.confirmAll(Arrays.asList((PeerBlockJournal.Status) null)), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.confirmAll(Arrays.asList(absent, absent)), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.confirmAll(Collections.nCopies(65, absent)), PeerBlockJournal.Code.INVALID_INPUT);
        rejected(() -> journal.confirmAll(Arrays.asList(status(ACCOUNT, true, 1, "demo-self"))), PeerBlockJournal.Code.INVALID_INPUT);
        check(FakeAndroidKeyStoreProvider.generations == 0 && AtomicFile.completedCommits == 0, "Invalid confirmations created state");
    }

    private static void statusRecreation(TestContext context) throws Exception {
        PeerBlockJournal.Status first = status(FIRST.peerId, true, 1, FIRST.mutationId);
        journal(context).confirm(first, "");
        sameStatus(first, journal(context).loadStatuses().get(0));
        byte[] before = ciphertext(context); journal(context).confirm(first, ""); equal(before, ciphertext(context));
        PeerBlockJournal.Status tombstone = status(FIRST.peerId, false, 2, "demo-unblocked");
        journal(context).confirm(tombstone, "");
        PeerBlockJournal recreated = journal(context);
        sameStatus(tombstone, recreated.loadStatuses().get(0));
        before = ciphertext(context);
        for (PeerBlockJournal.Status stale : Arrays.asList(first,
                status(FIRST.peerId, true, 2, tombstone.mutationId), status(FIRST.peerId, false, 2, "demo-changed-id"),
                new PeerBlockJournal.Status(FIRST.peerId, false, 0, "", false))) {
            rejected(() -> recreated.confirm(stale, ""), PeerBlockJournal.Code.STATE_CHANGED);
            equal(before, ciphertext(context));
        }
        rejected(() -> recreated.prepare(pending(FIRST.peerId, "demo-stale-request", 1, true)), PeerBlockJournal.Code.STATE_CHANGED);
        PeerBlockJournal.Pending retry = pending(FIRST.peerId, "demo-request-current", 2, true);
        recreated.prepare(retry);
        recreated.confirm(status(FIRST.peerId, true, 3, retry.mutationId), "");
        recreated.prepare(retry); // Exact pending retry remains valid after a status-only refresh.
        check(recreated.load().size() == 1, "Status refresh lost an unacknowledged exact retry");
        try { recreated.loadStatuses().clear(); fail("Mutable confirmed snapshot"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static void legacyStatus(TestContext context) throws Exception {
        PeerBlockJournal.Status legacy = new PeerBlockJournal.Status(FIRST.peerId, true, 0, "", true);
        journal(context).confirm(legacy, "");
        sameStatus(legacy, journal(context).loadStatuses().get(0));
        byte[] before = ciphertext(context); journal(context).confirm(legacy, ""); equal(before, ciphertext(context));
        rejected(() -> journal(context).confirm(new PeerBlockJournal.Status(FIRST.peerId, false, 0, "", false), ""), PeerBlockJournal.Code.STATE_CHANGED);
        PeerBlockJournal.Status tombstone = status(FIRST.peerId, false, 1, "demo-cleared-legacy");
        journal(context).confirm(tombstone, "");
        rejected(() -> journal(context).confirm(legacy, ""), PeerBlockJournal.Code.STATE_CHANGED);
        sameStatus(tombstone, journal(context).loadStatuses().get(0));
    }

    private static void atomicConfirm(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST); journal.prepare(OTHER);
        PeerBlockJournal.Status confirmed = status(FIRST.peerId, true, 1, FIRST.mutationId);
        int commits = AtomicFile.completedCommits;
        journal.confirm(confirmed, FIRST.mutationId);
        check(AtomicFile.completedCommits == commits + 1, "Confirm and ack used separate commits");
        sameStatus(confirmed, journal(context).loadStatuses().get(0));
        same(OTHER, journal(context).load().get(0));
        byte[] before = ciphertext(context);
        journal(context).confirm(confirmed, FIRST.mutationId);
        equal(before, ciphertext(context));
        PeerBlockJournal.Pending unblock = pending(FIRST.peerId, "demo-unblock", 1, false);
        journal.prepare(unblock);
        PeerBlockJournal.Status unblocked = status(FIRST.peerId, false, 2, unblock.mutationId);
        journal.confirm(unblocked, unblock.mutationId);
        sameStatus(unblocked, journal(context).loadStatuses().get(0));
        same(OTHER, journal(context).load().get(0));
    }

    private static void invalidConfirmation(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST);
        byte[] before = ciphertext(context);
        for (PeerBlockJournal.Status changed : Arrays.asList(
                status(FIRST.peerId, false, 1, FIRST.mutationId), status(FIRST.peerId, true, 2, FIRST.mutationId),
                status(OTHER.peerId, true, 1, FIRST.mutationId), status(FIRST.peerId, true, 1, "demo-other-id"))) {
            rejected(() -> journal.confirm(changed, FIRST.mutationId), PeerBlockJournal.Code.STALE_ACK);
            equal(before, ciphertext(context));
            check(journal.loadStatuses().isEmpty(), "Invalid acknowledgement partially stored status");
        }
        rejected(() -> journal.confirm(status(FIRST.peerId, true, 1, FIRST.mutationId), "demo-wrong-ack"), PeerBlockJournal.Code.STALE_ACK);
        journal.acknowledge(FIRST.peerId, FIRST.mutationId);
        rejected(() -> journal.confirm(status(FIRST.peerId, true, 1, FIRST.mutationId), FIRST.mutationId), PeerBlockJournal.Code.STALE_ACK);
        PeerBlockJournal.Pending maximum = pending(FIRST.peerId, "demo-max-revision", Integer.MAX_VALUE, true);
        journal.prepare(maximum);
        rejected(() -> journal.confirm(status(FIRST.peerId, true, Integer.MAX_VALUE, maximum.mutationId), maximum.mutationId), PeerBlockJournal.Code.STALE_ACK);
        check(journal.loadStatuses().isEmpty(), "Revision overflow accepted a confirmation");
    }

    private static void confirmedReplay(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST);
        PeerBlockJournal.Status confirmed = status(FIRST.peerId, true, 1, FIRST.mutationId);
        journal.confirm(confirmed, ""); // A GET can learn the status before the mutation response arrives.
        journal.confirm(confirmed, FIRST.mutationId);
        check(journal.load().isEmpty(), "Exact acknowledgement after GET did not clear pending");
        PeerBlockJournal.Pending newer = pending(FIRST.peerId, "demo-next-request", 1, false); journal.prepare(newer);
        byte[] before = ciphertext(context);
        rejected(() -> journal.confirm(confirmed, FIRST.mutationId), PeerBlockJournal.Code.STALE_ACK);
        equal(before, ciphertext(context)); same(newer, journal.load().get(0));
    }

    private static void statusPage(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST);
        List<PeerBlockJournal.Status> page = statusPage(0, 64);
        int commits = AtomicFile.completedCommits; journal.confirmAll(page);
        check(AtomicFile.completedCommits == commits + 1 && journal.loadStatuses().size() == 64, "Server page did not use one commit");
        same(FIRST, journal.load().get(0));
        byte[] before = ciphertext(context); Collections.reverse(page); journal.confirmAll(page); equal(before, ciphertext(context));
        journal.confirmAll(Arrays.asList(new PeerBlockJournal.Status("demo-absent-peer", false, 0, "", false)));
        equal(before, ciphertext(context));
    }

    private static void pageRegression(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context);
        journal.confirm(status(OTHER.peerId, false, 4, "demo-newer"), "");
        byte[] before = ciphertext(context);
        rejected(() -> journal.confirmAll(Arrays.asList(status(FIRST.peerId, true, 1, FIRST.mutationId),
                status(OTHER.peerId, true, 3, "demo-stale"))), PeerBlockJournal.Code.STATE_CHANGED);
        equal(before, ciphertext(context)); check(journal.loadStatuses().size() == 1, "Page regression committed partial updates");
    }

    private static void statusCapacity(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context);
        for (int start = 0; start < 1024; start += 64) journal.confirmAll(statusPage(start, 64));
        check(journal(context).loadStatuses().size() == 1024 && AtomicFile.completedCommits == 16, "Confirmed capacity lost rows or overcommitted");
        for (int i = 0; i < 8; i++) journal.prepare(pending("demo-pending-" + i, "demo-request-" + i, 0, true));
        byte[] before = ciphertext(context);
        rejected(() -> journal.confirm(status("demo-one-too-many", true, 1, "demo-overflow"), ""), PeerBlockJournal.Code.CAPACITY_REACHED);
        equal(before, ciphertext(context));
        journal.confirm(status("demo-status-0", false, 2, "demo-newer-zero"), "");
        check(journal(context).loadStatuses().size() == 1024 && journal(context).load().size() == 8, "Updating a full journal lost state");
        check(ciphertext(context).length > 4096 && ciphertext(context).length < 256 * 1024, "Wrong encrypted journal capacity");
        before = ciphertext(context);
        rejected(() -> journal.confirmAll(Arrays.asList(status("demo-status-1", true, 2, "demo-update-one"),
                status("demo-overflow-page", true, 1, "demo-overflow"))), PeerBlockJournal.Code.CAPACITY_REACHED);
        equal(before, ciphertext(context));
    }

    private static void confirmRace(TestContext context) throws Exception {
        PeerBlockJournal.Status incoming = status(FIRST.peerId, true, 1, FIRST.mutationId);
        PeerBlockJournal.Status newer = status(FIRST.peerId, false, 2, "demo-newer-status");
        context.raceBeforeCommit(() -> journal(context).confirm(newer, ""));
        rejected(() -> journal(context).confirm(incoming, ""), PeerBlockJournal.Code.STATE_CHANGED);
        sameStatus(newer, journal(context).loadStatuses().get(0));
    }

    private static void pageRace(TestContext context) throws Exception {
        context.raceBeforeCommit(() -> journal(context).prepare(FIRST));
        rejected(() -> journal(context).confirmAll(statusPage(0, 64)), PeerBlockJournal.Code.STATE_CHANGED);
        same(FIRST, journal(context).load().get(0));
        check(journal(context).loadStatuses().isEmpty(), "Failed page CAS committed partial status");
    }

    private static void uncertainConfirmation(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context); journal.prepare(FIRST); journal.prepare(OTHER);
        PeerBlockJournal.Status confirmed = status(FIRST.peerId, true, 1, FIRST.mutationId);
        Os.inject(Os.Fault.FSYNC, 3);
        rejected(() -> journal.confirm(confirmed, FIRST.mutationId), PeerBlockJournal.Code.IO_FAILED);
        byte[] committed = ciphertext(context);
        ordinaryOperationsFail(context, PeerBlockJournal.Code.IO_FAILED);
        Os.recover();
        sameStatus(confirmed, journal(context).loadStatuses().get(0)); same(OTHER, journal(context).load().get(0));
        journal(context).confirm(confirmed, FIRST.mutationId); equal(committed, ciphertext(context));
        check(AtomicFile.completedCommits == 3, "Uncertain confirmation was rewritten");
    }

    private static void forgetPreservesStatus(TestContext context) throws Exception {
        PeerBlockJournal journal = journal(context);
        PeerBlockJournal.Status confirmed = status(FIRST.peerId, false, 5, "demo-floor");
        journal.confirm(confirmed, "");
        PeerBlockJournal.Pending pending = pending(FIRST.peerId, "demo-pending-new", 5, true); journal.prepare(pending);
        journal.acknowledge(pending.peerId, pending.mutationId);
        check(journal(context).load().isEmpty(), "Local forget retained pending request");
        sameStatus(confirmed, journal(context).loadStatuses().get(0));
        rejected(() -> journal(context).confirm(status(FIRST.peerId, true, 4, "demo-stale-floor"), ""), PeerBlockJournal.Code.STATE_CHANGED);
    }

    private static List<PeerBlockJournal.Status> statusPage(int start, int count) {
        List<PeerBlockJournal.Status> statuses = new ArrayList<>();
        for (int i = start; i < start + count; i++) statuses.add(status("demo-status-" + i, i % 2 == 0, 1, "demo-mutation-" + i));
        return statuses;
    }

    private static PeerBlockJournal.Status status(String peer, boolean blocked, int revision, String mutation) {
        return new PeerBlockJournal.Status(peer, blocked, revision, mutation, false);
    }

    private static void sameStatus(PeerBlockJournal.Status a, PeerBlockJournal.Status b) {
        check(a.peerId.equals(b.peerId) && a.blocked == b.blocked && a.revision == b.revision
                && a.mutationId.equals(b.mutationId) && a.legacyHold == b.legacyHold, "Confirmed status changed");
    }

    private static void ordinaryOperationsFail(TestContext context, PeerBlockJournal.Code code) throws Exception {
        byte[] before = ciphertext(context); int commits = AtomicFile.completedCommits;
        int generations = FakeAndroidKeyStoreProvider.generations, deletions = FakeAndroidKeyStoreProvider.deletions;
        PeerBlockJournal journal = journal(context);
        rejected(journal::load, code);
        rejected(journal::loadStatuses, code);
        rejected(() -> journal.prepare(FIRST), code);
        rejected(() -> journal.acknowledge(FIRST.peerId, FIRST.mutationId), code);
        rejected(() -> journal.confirm(status(FIRST.peerId, true, 1, FIRST.mutationId), ""), code);
        rejected(() -> journal.confirmAll(Collections.emptyList()), code);
        equal(before, ciphertext(context));
        check(commits == AtomicFile.completedCommits && generations == FakeAndroidKeyStoreProvider.generations
                && deletions == FakeAndroidKeyStoreProvider.deletions, "Fail-closed operation changed state or keys");
    }

    private static PeerBlockJournal.Pending pending(String peer, String mutation, int revision, boolean blocked) {
        return new PeerBlockJournal.Pending(peer, mutation, revision, blocked);
    }
    private static PeerBlockJournal journal(TestContext context) { return new PeerBlockJournal(context, ORIGIN, ACCOUNT); }
    private static KeystoreBlobStore raw(TestContext context) { return new KeystoreBlobStore(context, PeerBlockJournal.namespace(ORIGIN, ACCOUNT)); }
    private static Path path(TestContext context) { return path(context, ORIGIN, ACCOUNT); }
    private static Path path(TestContext context, String origin, String account) {
        return context.directory.resolve("tortalk-" + PeerBlockJournal.namespace(origin, account) + "-v1.enc");
    }
    private static byte[] ciphertext(TestContext context) throws Exception { return Files.readAllBytes(path(context)); }
    private static void same(PeerBlockJournal.Pending a, PeerBlockJournal.Pending b) {
        check(a.peerId.equals(b.peerId) && a.mutationId.equals(b.mutationId)
                && a.expectedRevision == b.expectedRevision && a.blocked == b.blocked, "Pending request changed");
    }
    private static void equal(byte[] a, byte[] b) { check(Arrays.equals(a, b), "Stored bytes changed"); }
    private static void check(boolean value, String text) { if (!value) fail(text); }
    private static void fail(String text) { throw new AssertionError(text); }
    private interface Attempt { void run() throws Exception; }
    private interface Scenario { void run(TestContext context) throws Exception; }
    private static void rejected(Attempt action, PeerBlockJournal.Code code) throws Exception {
        try { action.run(); fail("Journal failure was incorrectly accepted"); }
        catch (PeerBlockJournal.JournalException error) {
            check(error.code == code && error.getMessage().equals(code.name()) && error.getCause() == null, "Wrong journal error code");
        }
    }
    private static void run(Path root, String label, Scenario action) throws Exception {
        AtomicFile.reset(); Os.reset(); FakeAndroidKeyStoreProvider.reset();
        Path directory = Files.createTempDirectory(root, "synthetic-block-journal-");
        try {
            action.run(new TestContext(directory));
            check(Os.openDescriptorCount() == 0, "Directory descriptor leaked");
            scenarios++; System.out.println("PASS " + label);
        } finally {
            FakeAndroidKeyStoreProvider.reset();
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    private static final class TestContext extends Context {
        final Path directory;
        private int lookupsUntilRace;
        private Attempt race;
        TestContext(Path directory) { this.directory = directory; }
        // First directory lookup is the snapshot read; second is before the write lock.
        // The hook lives entirely in this explicit Android Context double, not production.
        void raceBeforeCommit(Attempt race) { this.race = race; lookupsUntilRace = 2; }
        @Override public Context getApplicationContext() { return this; }
        @Override public String getPackageName() { return "dev.gyeopdam.embeddedtor.blockjournalhost"; }
        @Override public File getNoBackupFilesDir() {
            if (race != null && --lookupsUntilRace == 0) {
                Attempt pending = race; race = null;
                try { pending.run(); }
                catch (Exception error) { throw new AssertionError("Synthetic concurrent operation failed", error); }
            }
            return directory.toFile();
        }
    }
}
