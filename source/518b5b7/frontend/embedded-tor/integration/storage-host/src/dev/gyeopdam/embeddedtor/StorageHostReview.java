package dev.gyeopdam.embeddedtor;

import android.content.Context;
import android.system.Os;
import android.util.AtomicFile;
import dev.gyeopdam.embeddedtor.host.FakeAndroidKeyStoreProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.Security;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Runs unchanged production KeystoreBlobStore against explicitly labeled host Android doubles. */
public final class StorageHostReview {
    private static final byte[] FIRST = "SYNTHETIC_PENDING_RATCHET_AND_EXACT_OUTBOX_BYTES_A".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NEXT = "SYNTHETIC_PENDING_RATCHET_AND_EXACT_OUTBOX_BYTES_B".getBytes(StandardCharsets.US_ASCII);
    private static int passed;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected private host-test work directory");
        if (Security.getProvider("AndroidKeyStore") != null) throw new AssertionError("Refusing to replace an existing provider");
        FakeAndroidKeyStoreProvider provider = new FakeAndroidKeyStoreProvider();
        Security.addProvider(provider);
        try {
            Path root = Path.of(args[0]);
            run(root, "read absent state creates no key", StorageHostReview::missingRead);
            run(root, "successful commit and read cross directory barriers", StorageHostReview::successfulWrite);
            run(root, "post-rename fsync failure preserves bytes and blocks release", StorageHostReview::fsyncFailure);
            run(root, "read-or-create failure resumes exact initial state", StorageHostReview::creationRetry);
            run(root, "overwrite post-rename failure preserves new committed state", StorageHostReview::overwriteFailure);
            run(root, "finishWrite exception after replacement never rolls back", StorageHostReview::finishWriteFailure);
            run(root, "read file-sync failure blocks release and preserves state", StorageHostReview::readFileSyncFailure);
            run(root, "directory open failure preserves committed state", c -> syscallFailure(c, Os.Fault.OPEN));
            run(root, "directory fstat failure preserves committed state", c -> syscallFailure(c, Os.Fault.FSTAT));
            run(root, "directory close failure propagates and blocks read", c -> syscallFailure(c, Os.Fault.CLOSE));
            run(root, "non-directory descriptor rejected before fsync", StorageHostReview::nonDirectory);
            run(root, "clear barrier failure is reported after cryptographic deletion", StorageHostReview::clearFailure);
            run(root, "legacy backup recovery requires successful read barrier", StorageHostReview::backupRecovery);
            run(root, "corrupt ciphertext cannot be overwritten", StorageHostReview::corruptState);
            run(root, "missing key cannot regenerate over ciphertext", StorageHostReview::missingKey);
            run(root, "cross-namespace copied ciphertext is rejected", StorageHostReview::namespaceBinding);
            run(root, "partial first-write residue is not absence", StorageHostReview::partialWrite);
            run(root, "bounds and namespace inputs fail before key generation", StorageHostReview::bounds);
            run(root, "clear then explicit write generates a fresh key", StorageHostReview::clearAndRewrite);
            System.out.println("PASS: " + passed + " production KeystoreBlobStore host regression scenarios");
            System.out.println("DOUBLES: Context, AtomicFile, Os, AndroidKeyStore provider; synthetic memory-only keys.");
            System.out.println("NOT VERIFIED: actual Android/Keystore, directory-fsync implementation, power loss, hardware isolation, or physical erasure.");
        } finally {
            FakeAndroidKeyStoreProvider.reset();
            if (Security.getProvider("AndroidKeyStore") == provider) Security.removeProvider("AndroidKeyStore");
        }
    }

    private static void missingRead(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context);
        check(store.read() == null, "Absent state was not null");
        check(FakeAndroidKeyStoreProvider.generations == 0 && FakeAndroidKeyStoreProvider.keyCount() == 0, "Read generated a key");
        check(AtomicFile.completedCommits == 0 && Os.openCalls == 0, "Read committed absent state");
    }

    private static void successfulWrite(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context);
        store.write(FIRST);
        check(AtomicFile.completedCommits == 1 && Os.successfulFsyncs == 1, "Commit omitted directory barrier");
        check(FakeAndroidKeyStoreProvider.generations == 1, "Wrong key-generation count");
        equal(FIRST, store.read());
        check(Os.successfulFsyncs == 2, "Read omitted directory barrier");
        check(!new String(ciphertext(context), StandardCharsets.ISO_8859_1)
                .contains(new String(FIRST, StandardCharsets.US_ASCII)), "Plaintext reached ciphertext file");
    }

    private static void fsyncFailure(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context);
        Os.inject(Os.Fault.FSYNC, 1);
        rejected(() -> store.write(FIRST), KeystoreBlobStore.Code.IO_FAILED);
        check(AtomicFile.completedCommits == 1, "Failure happened before committed rename");
        byte[] committed = ciphertext(context);
        check(AtomicFile.failWriteCalls == 0, "Post-commit failure attempted destructive rollback");
        int keyReads = FakeAndroidKeyStoreProvider.keyReads;
        rejected(store::read, KeystoreBlobStore.Code.IO_FAILED);
        rejected(store(context)::read, KeystoreBlobStore.Code.IO_FAILED);
        check(FakeAndroidKeyStoreProvider.keyReads == keyReads, "Read reached key lookup before barrier success");
        AtomicInteger initialized = new AtomicInteger();
        rejected(() -> store.readOrCreate(() -> { initialized.incrementAndGet(); return NEXT.clone(); }), KeystoreBlobStore.Code.IO_FAILED);
        rejected(() -> store.write(NEXT), KeystoreBlobStore.Code.IO_FAILED);
        check(initialized.get() == 0, "Blocked retry created replacement state");
        equal(committed, ciphertext(context));
        check(AtomicFile.completedCommits == 1 && FakeAndroidKeyStoreProvider.generations == 1, "Blocked retry changed durable state");
        Os.recover();
        equal(FIRST, store.read());
        equal(FIRST, store.readOrCreate(() -> { throw new AssertionError("Existing state reinitialized"); }));
        equal(committed, ciphertext(context));
        check(AtomicFile.completedCommits == 1, "Recovered read rewrote original bytes");
    }

    private static void creationRetry(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context);
        AtomicInteger initialized = new AtomicInteger();
        Os.inject(Os.Fault.FSYNC, 1);
        rejected(() -> store.readOrCreate(() -> { initialized.incrementAndGet(); return FIRST.clone(); }), KeystoreBlobStore.Code.IO_FAILED);
        byte[] committed = ciphertext(context);
        rejected(() -> store.readOrCreate(() -> { initialized.incrementAndGet(); return NEXT.clone(); }), KeystoreBlobStore.Code.IO_FAILED);
        check(initialized.get() == 1, "A failed creation was regenerated on retry");
        Os.recover();
        equal(FIRST, store(context).readOrCreate(() -> { initialized.incrementAndGet(); return NEXT.clone(); }));
        equal(committed, ciphertext(context));
        check(initialized.get() == 1, "Successful resume regenerated pending credentials");
    }

    private static void overwriteFailure(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context); store.write(FIRST);
        byte[] before = ciphertext(context);
        Os.inject(Os.Fault.FSYNC, 2); // Permit the old-state read barrier; fail only after replacement.
        rejected(() -> store.write(NEXT), KeystoreBlobStore.Code.IO_FAILED);
        byte[] after = ciphertext(context);
        check(!Arrays.equals(before, after) && AtomicFile.completedCommits == 2, "Replacement did not commit before injected fault");
        rejected(store::read, KeystoreBlobStore.Code.IO_FAILED);
        equal(after, ciphertext(context));
        check(AtomicFile.failWriteCalls == 0, "Committed replacement rolled back");
        Os.recover(); equal(NEXT, store.read()); equal(after, ciphertext(context));
    }

    private static void syscallFailure(TestContext context, Os.Fault fault) throws Exception {
        KeystoreBlobStore store = store(context);
        Os.inject(fault, 1);
        rejected(() -> store.write(FIRST), KeystoreBlobStore.Code.IO_FAILED);
        byte[] committed = ciphertext(context);
        check(AtomicFile.completedCommits == 1 && AtomicFile.failWriteCalls == 0, "Committed bytes were rolled back");
        rejected(store::read, KeystoreBlobStore.Code.IO_FAILED);
        equal(committed, ciphertext(context));
        Os.recover(); equal(FIRST, store.read()); equal(committed, ciphertext(context));
    }

    private static void finishWriteFailure(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context);
        AtomicFile.throwAfterReplacement = true;
        rejected(() -> store.write(FIRST), KeystoreBlobStore.Code.IO_FAILED);
        check(AtomicFile.completedCommits == 1, "finishWrite exception occurred before replacement");
        check(AtomicFile.failWriteCalls == 0, "Uncertain finishWrite triggered destructive rollback");
        check(Os.successfulFsyncs == 0, "Fixture did not skip post-finish directory barrier");
        byte[] committed = ciphertext(context);
        Os.inject(Os.Fault.FSYNC, 1);
        rejected(store::read, KeystoreBlobStore.Code.IO_FAILED);
        rejected(() -> store.readOrCreate(() -> { throw new AssertionError("Uncertain state was reinitialized"); }),
                KeystoreBlobStore.Code.IO_FAILED);
        equal(committed, ciphertext(context));
        AtomicFile.throwAfterReplacement = false; Os.recover();
        equal(FIRST, store(context).read()); equal(committed, ciphertext(context));
        check(AtomicFile.completedCommits == 1 && FakeAndroidKeyStoreProvider.generations == 1, "Recovery changed staged state");
    }

    private static void readFileSyncFailure(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context); store.write(FIRST);
        byte[] committed = ciphertext(context);
        int directoryOpens = Os.openCalls;
        int keyReads = FakeAndroidKeyStoreProvider.keyReads;
        AtomicFile.closeReadStreamAtEof = true;
        rejected(store::read, KeystoreBlobStore.Code.IO_FAILED);
        rejected(store(context)::read, KeystoreBlobStore.Code.IO_FAILED);
        rejected(() -> store.readOrCreate(() -> { throw new AssertionError("Unsynced file was discarded and reinitialized"); }),
                KeystoreBlobStore.Code.IO_FAILED);
        rejected(() -> store.write(NEXT), KeystoreBlobStore.Code.IO_FAILED);
        check(Os.openCalls == directoryOpens, "Failed file barrier proceeded to directory release barrier");
        check(FakeAndroidKeyStoreProvider.keyReads == keyReads, "Failed file barrier reached decryption key");
        check(AtomicFile.completedCommits == 1 && FakeAndroidKeyStoreProvider.generations == 1, "Read sync failure reset state");
        equal(committed, ciphertext(context));
        AtomicFile.closeReadStreamAtEof = false;
        equal(FIRST, store.read()); equal(committed, ciphertext(context));
    }

    private static void nonDirectory(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context);
        Os.returnNonDirectory = true;
        rejected(() -> store.write(FIRST), KeystoreBlobStore.Code.IO_FAILED);
        check(Os.fstatCalls == 1 && Os.fsyncCalls == 0 && Os.closeCalls == 1, "Non-directory rejection bypassed stat or cleanup");
        byte[] committed = ciphertext(context);
        rejected(store::read, KeystoreBlobStore.Code.IO_FAILED);
        equal(committed, ciphertext(context));
        Os.returnNonDirectory = false; equal(FIRST, store.read());
    }

    private static void clearFailure(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context); store.write(FIRST);
        Os.inject(Os.Fault.FSYNC, 1);
        rejected(store::clear, KeystoreBlobStore.Code.IO_FAILED);
        check(!base(context).exists() && FakeAndroidKeyStoreProvider.keyCount() == 0, "Clear left ciphertext or usable key");
        check(FakeAndroidKeyStoreProvider.deletions == 1, "Clear did not delete key");
        Os.recover(); store.clear(); check(store.read() == null, "Clear retry did not converge");
    }

    private static void backupRecovery(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context); store.write(FIRST);
        byte[] original = ciphertext(context);
        Files.move(base(context).toPath(), Path.of(base(context).getPath() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        Files.write(base(context).toPath(), new byte[] {1, 2, 3});
        Os.inject(Os.Fault.FSYNC, 1);
        rejected(store::read, KeystoreBlobStore.Code.IO_FAILED);
        check(AtomicFile.backupRecoveries == 1, "Backup recovery was not exercised");
        equal(original, ciphertext(context));
        Os.recover(); equal(FIRST, store.read());
    }

    private static void corruptState(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context); store.write(FIRST);
        byte[] corrupt = ciphertext(context); corrupt[corrupt.length - 1] ^= 1; Files.write(base(context).toPath(), corrupt);
        rejected(store::read, KeystoreBlobStore.Code.CORRUPT_STATE);
        rejected(() -> store.write(NEXT), KeystoreBlobStore.Code.CORRUPT_STATE);
        equal(corrupt, ciphertext(context));
        check(FakeAndroidKeyStoreProvider.generations == 1, "Corrupt state triggered key replacement");
    }

    private static void missingKey(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context); store.write(FIRST);
        byte[] committed = ciphertext(context); FakeAndroidKeyStoreProvider.loseKeys();
        rejected(store::read, KeystoreBlobStore.Code.KEY_UNAVAILABLE);
        rejected(() -> store.write(NEXT), KeystoreBlobStore.Code.KEY_UNAVAILABLE);
        equal(committed, ciphertext(context));
        check(FakeAndroidKeyStoreProvider.generations == 1, "Missing key silently regenerated over existing ciphertext");
    }

    private static void namespaceBinding(TestContext context) throws Exception {
        KeystoreBlobStore first = store(context);
        KeystoreBlobStore second = new KeystoreBlobStore(context, "enrollment");
        first.write(FIRST); second.write(NEXT);
        Path secondPath = context.directory.resolve("tortalk-enrollment-v1.enc");
        Files.copy(base(context).toPath(), secondPath, StandardCopyOption.REPLACE_EXISTING);
        rejected(second::read, KeystoreBlobStore.Code.CORRUPT_STATE);
        rejected(() -> second.write(NEXT), KeystoreBlobStore.Code.CORRUPT_STATE);
        equal(FIRST, first.read());
    }

    private static void partialWrite(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context);
        Path partial = Path.of(base(context).getPath() + ".new"); Files.write(partial, new byte[] {1, 2, 3});
        rejected(store::read, KeystoreBlobStore.Code.IO_FAILED);
        rejected(() -> store.write(FIRST), KeystoreBlobStore.Code.IO_FAILED);
        equal(new byte[] {1, 2, 3}, Files.readAllBytes(partial));
        check(FakeAndroidKeyStoreProvider.generations == 0, "Partial state triggered key generation");
    }

    private static void bounds(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context);
        rejected(() -> store.write(new byte[4097]), KeystoreBlobStore.Code.INVALID_INPUT);
        rejected(() -> store.write(null), KeystoreBlobStore.Code.INVALID_INPUT);
        try { new KeystoreBlobStore(context, "../outside"); throw new AssertionError("Unsafe namespace accepted"); }
        catch (IllegalArgumentException expected) { check("INVALID_STORAGE_NAMESPACE".equals(expected.getMessage()), "Unexpected namespace error"); }
        check(FakeAndroidKeyStoreProvider.generations == 0, "Invalid input generated a key");
    }

    private static void clearAndRewrite(TestContext context) throws Exception {
        KeystoreBlobStore store = store(context); store.write(FIRST); byte[] first = ciphertext(context);
        store.clear(); check(store.read() == null, "Clear retained readable state");
        store.write(FIRST); equal(FIRST, store.read());
        check(FakeAndroidKeyStoreProvider.generations == 2, "Explicit clear did not rotate key on next save");
        check(!Arrays.equals(first, ciphertext(context)), "Re-created state reused ciphertext");
    }

    private static KeystoreBlobStore store(TestContext context) { return new KeystoreBlobStore(context, "session"); }
    private static File base(TestContext context) { return context.directory.resolve("tortalk-session-v1.enc").toFile(); }
    private static byte[] ciphertext(TestContext context) throws Exception { return Files.readAllBytes(base(context).toPath()); }
    private static void equal(byte[] expected, byte[] actual) { check(Arrays.equals(expected, actual), "Byte-for-byte state mismatch"); }
    private static void check(boolean condition, String failure) { if (!condition) throw new AssertionError(failure); }
    private interface Attempt { void run() throws Exception; }
    private interface Scenario { void run(TestContext context) throws Exception; }
    private static void rejected(Attempt action, KeystoreBlobStore.Code code) throws Exception {
        try { action.run(); throw new AssertionError("Storage failure was incorrectly accepted"); }
        catch (KeystoreBlobStore.StorageException expected) {
            check(expected.code == code && expected.getMessage().equals(code.name()), "Unexpected storage error code");
            check(expected.getCause() == null, "Provider exception details were retained");
        }
    }
    private static void run(Path root, String name, Scenario scenario) throws Exception {
        AtomicFile.reset(); Os.reset(); FakeAndroidKeyStoreProvider.reset();
        Path directory = Files.createTempDirectory(root, "synthetic-storage-");
        try {
            scenario.run(new TestContext(directory));
            check(Os.openDescriptorCount() == 0, "Directory descriptor not closed");
            passed++; System.out.println("PASS " + name);
        } finally {
            FakeAndroidKeyStoreProvider.reset();
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }
    private static final class TestContext extends Context {
        final Path directory;
        TestContext(Path directory) { this.directory = directory; }
        @Override public Context getApplicationContext() { return this; }
        @Override public String getPackageName() { return "dev.gyeopdam.embeddedtor.hostreview"; }
        @Override public File getNoBackupFilesDir() { return directory.toFile(); }
    }
}
