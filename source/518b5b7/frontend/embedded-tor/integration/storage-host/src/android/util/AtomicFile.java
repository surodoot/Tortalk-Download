package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * HOST TEST DOUBLE: completes a file-fsynced atomic rename but deliberately omits directory fsync.
 * Also models legacy .bak recovery. It is not Android's actual AtomicFile implementation.
 */
public final class AtomicFile {
    public static int completedCommits, failWriteCalls, backupRecoveries;
    public static boolean throwAfterReplacement;
    public static boolean closeReadStreamAtEof;
    private final File base;
    public AtomicFile(File base) { this.base = base; }
    public static void reset() {
        completedCommits = 0; failWriteCalls = 0; backupRecoveries = 0;
        throwAfterReplacement = false; closeReadStreamAtEof = false;
    }
    public File getBaseFile() { return base; }
    private File pending() { return new File(base.getPath() + ".new"); }
    private File backup() { return new File(base.getPath() + ".bak"); }
    public FileOutputStream startWrite() throws IOException { return new FileOutputStream(pending()); }
    public void finishWrite(FileOutputStream output) {
        try {
            output.getFD().sync(); output.close();
            Files.move(pending().toPath(), base.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            completedCommits++;
            if (throwAfterReplacement) throw new IllegalStateException("SYNTHETIC_FINISH_WRITE_AFTER_REPLACEMENT");
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    public void failWrite(FileOutputStream output) {
        failWriteCalls++;
        try { output.close(); Files.deleteIfExists(pending().toPath()); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
    public FileInputStream openRead() throws FileNotFoundException {
        if (backup().exists()) {
            try {
                Files.move(backup().toPath(), base.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                backupRecoveries++;
            } catch (IOException error) { throw new UncheckedIOException(error); }
        }
        if (!closeReadStreamAtEof) return new FileInputStream(base);
        // FileDescriptor.sync is final/native. Closing only after the complete bounded read
        // injects a deterministic failure at production's following getFD().sync() barrier.
        return new FileInputStream(base) {
            @Override public int read(byte[] bytes) throws IOException {
                int count = super.read(bytes);
                if (count == -1) close();
                return count;
            }
        };
    }
    public void delete() {
        try {
            Files.deleteIfExists(base.toPath()); Files.deleteIfExists(pending().toPath()); Files.deleteIfExists(backup().toPath());
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
