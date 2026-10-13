package android.system;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileDescriptor;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * HOST TEST DOUBLE: fake directory descriptors and deterministic open/fsync/close faults.
 * It does not perform a real directory fsync or simulate power loss/filesystem persistence.
 */
public final class Os {
    public enum Fault { NONE, OPEN, FSTAT, FSYNC, CLOSE }
    private static final Map<FileDescriptor, String> OPEN = Collections.synchronizedMap(new IdentityHashMap<>());
    private static Fault fault = Fault.NONE;
    private static int afterCommit;
    public static int openCalls, fstatCalls, fsyncCalls, successfulFsyncs, closeCalls;
    public static boolean returnNonDirectory;
    private Os() { }

    public static void reset() {
        OPEN.clear(); fault = Fault.NONE; afterCommit = 0;
        openCalls = 0; fstatCalls = 0; fsyncCalls = 0; successfulFsyncs = 0; closeCalls = 0;
        returnNonDirectory = false;
    }
    public static void inject(Fault value, int minimumCompletedCommits) {
        fault = value; afterCommit = minimumCompletedCommits;
    }
    public static void recover() { fault = Fault.NONE; }
    public static int openDescriptorCount() { return OPEN.size(); }
    private static boolean fails(Fault operation) {
        return fault == operation && AtomicFile.completedCommits >= afterCommit;
    }
    public static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        openCalls++;
        if (!new File(path).isDirectory()
                || flags != (OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW)
                || mode != 0) throw new AssertionError("Invalid production directory-open arguments");
        if (fails(Fault.OPEN)) throw new ErrnoException("OPEN", OsConstants.EIO);
        FileDescriptor descriptor = new FileDescriptor();
        OPEN.put(descriptor, path);
        return descriptor;
    }
    public static StructStat fstat(FileDescriptor descriptor) throws ErrnoException {
        fstatCalls++;
        if (!OPEN.containsKey(descriptor)) throw new AssertionError("Unknown directory descriptor");
        if (fails(Fault.FSTAT)) throw new ErrnoException("FSTAT", OsConstants.EIO);
        return new StructStat(returnNonDirectory ? OsConstants.HOST_REGULAR_FILE : OsConstants.HOST_DIRECTORY);
    }
    public static void fsync(FileDescriptor descriptor) throws ErrnoException {
        fsyncCalls++;
        if (!OPEN.containsKey(descriptor)) throw new AssertionError("Unknown directory descriptor");
        if (fails(Fault.FSYNC)) throw new ErrnoException("FSYNC", OsConstants.EIO);
        successfulFsyncs++;
    }
    public static void close(FileDescriptor descriptor) throws ErrnoException {
        closeCalls++;
        if (OPEN.remove(descriptor) == null) throw new AssertionError("Directory descriptor closed twice");
        if (fails(Fault.CLOSE)) throw new ErrnoException("CLOSE", OsConstants.EIO);
    }
}
