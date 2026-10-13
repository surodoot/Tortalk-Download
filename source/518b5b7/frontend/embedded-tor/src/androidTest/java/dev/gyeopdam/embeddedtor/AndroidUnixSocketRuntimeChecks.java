package dev.gyeopdam.embeddedtor;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Fresh same-process synthetic filesystem socket only. No Tor START or remote traffic. */
final class AndroidUnixSocketRuntimeChecks {
    static final String SCOPE = "same_process_same_uid_api35_x86_64_fixture";
    static final String CHECKS = "filesystem_type_mode,kernel_same_uid_pid,wrong_pid_zero_bytes,"
            + "connect_backlog_deadline,idle_read_deadline,shutdown_eof,owned_fixture_cleanup";
    private static final int FIXTURE_TIMEOUT_MS = 500;

    static void run(Context context) throws Exception {
        String path = TorSocketPaths.socksPath(context);
        File socketFile = new File(path), directory = socketFile.getParentFile();
        requireAbsent(directory.getPath(), "UNIX_FRESH_FIXTURE_REQUIRED");
        int uid = Process.myUid(), pid = Process.myPid();
        TorSocketEndpoint endpoint = new TorSocketEndpoint(path, uid, pid);
        // Atomic creation establishes exclusive fixture ownership. Never adopt or
        // recursively delete the production path if a previous group created it.
        Os.mkdir(directory.getPath(), 0700);
        StructStat ownedDirectory = Os.lstat(directory.getPath());
        try {
            TorSocketPaths.prepare(context, uid);
            try (FileOutputStream ignored = new FileOutputStream(socketFile)) { }
            expectRejected(() -> TorSocketPaths.verify(endpoint));
            require(socketFile.isFile(), "UNIX_REGULAR_FILE_PRESERVED");
            require(socketFile.delete(), "UNIX_FIXTURE_DELETE_FAILED");
            Os.chmod(directory.getPath(), 0755);
            expectRejected(() -> TorSocketPaths.verifyDirectoryForPath(path, uid));
            Os.chmod(directory.getPath(), 0700);
            connectedIo(endpoint);
        } finally {
            StructStat current = Os.lstat(directory.getPath());
            require(current.st_dev == ownedDirectory.st_dev && current.st_ino == ownedDirectory.st_ino
                    && current.st_uid == uid && OsConstants.S_ISDIR(current.st_mode), "UNIX_FIXTURE_OWNER_CHANGED");
            // Exactly one newly owned directory and its fixed child, never a tree walk.
            require(!socketFile.exists() || socketFile.delete(), "UNIX_FIXTURE_DELETE_FAILED");
            require(directory.delete(), "UNIX_FIXTURE_DELETE_FAILED");
            requireAbsent(directory.getPath(), "UNIX_FIXTURE_CLEANUP_REQUIRED");
        }
    }

    private static void connectedIo(TorSocketEndpoint endpoint) throws Exception {
        FileDescriptor listenerDescriptor;
        try (LocalSocket listenerOwner = new LocalSocket()) {
            listenerOwner.bind(new LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM));
            listenerDescriptor = listenerOwner.getFileDescriptor();
            listenerOwner.setSoTimeout(FIXTURE_TIMEOUT_MS);
            Os.chmod(endpoint.path, 0600);
            // API26 LocalServerSocket(fd) borrows this FD. LocalSocketImpl.close()
            // clears the wrapper only when mFdCreatedInternally is false. Keep
            // listenerOwner as sole owner; accepted sockets own their new FDs.
            LocalServerSocket server = new LocalServerSocket(listenerDescriptor);
            try {
                TorSocketPaths.verify(endpoint);
                connectedBytesAndShutdown(endpoint, server);
                wrongPidSendsNoBytes(endpoint, server);
                backlogDeadline(endpoint, listenerDescriptor);
            } finally {
                server.close();
                require(server.getFileDescriptor() == null, "UNIX_BORROWED_WRAPPER_CLOSED");
                // The wrapper close must not close the original owner's descriptor.
                require(listenerDescriptor.valid(), "UNIX_LISTENER_OWNER_REQUIRED");
                Os.fstat(listenerDescriptor);
            }
        }
        require(!listenerDescriptor.valid(), "UNIX_LISTENER_CLOSE_REQUIRED");
    }

    private static void connectedBytesAndShutdown(TorSocketEndpoint endpoint, LocalServerSocket server) throws Exception {
        AndroidTorSocket client = new AndroidTorSocket(endpoint);
        try {
            client.connect(FIXTURE_TIMEOUT_MS);
            require(client.peerUid() == endpoint.uid && client.peerPid() == endpoint.pid, "UNIX_KERNEL_PEER_REQUIRED");
            FileDescriptor acceptedDescriptor;
            try (LocalSocket accepted = acceptBounded(server)) {
                acceptedDescriptor = accepted.getFileDescriptor();
                accepted.setSoTimeout(FIXTURE_TIMEOUT_MS);
                byte[] buffer = new byte[8];
                long began = System.nanoTime();
                require(client.read(buffer, buffer.length, 50) == 0, "UNIX_IDLE_READ_MUST_BE_BOUNDED");
                long elapsed = elapsedMillis(began);
                require(elapsed >= 25 && elapsed < 1000, "UNIX_IDLE_READ_DEADLINE_REQUIRED");
                require(client.awaitWritable(100), "UNIX_WRITE_READY_REQUIRED");
                int written = client.write(new byte[]{1, 2, 3}, 3);
                require(written > 0 && written <= 3, "UNIX_SYNTHETIC_SEND_REQUIRED");
                new java.io.DataInputStream(accepted.getInputStream()).readFully(buffer, 0, written);
                for (int i = 0; i < written; i++) require(buffer[i] == i + 1, "UNIX_SYNTHETIC_RECEIVE_REQUIRED");
                accepted.getOutputStream().write(new byte[]{4});
                require(client.read(buffer, buffer.length, FIXTURE_TIMEOUT_MS) == 1 && buffer[0] == 4,
                        "UNIX_NONBLOCKING_READ_REQUIRED");
                CountDownLatch entered = new CountDownLatch(1);
                ExecutorService owner = Executors.newSingleThreadExecutor();
                Future<Integer> reading = null;
                try {
                    reading = owner.submit(() -> {
                        entered.countDown();
                        return client.read(new byte[1], 1, 5000);
                    });
                    require(entered.await(1, TimeUnit.SECONDS), "UNIX_READ_OWNER_REQUIRED");
                    client.shutdown();
                    // Proves bounded EOF after shutdown. Scheduling does not prove
                    // the reader was already inside the native poll at shutdown.
                    require(reading.get(1, TimeUnit.SECONDS) == -1, "UNIX_SHUTDOWN_EOF_REQUIRED");
                } finally {
                    client.shutdown();
                    if (reading != null) reading.cancel(true);
                    owner.shutdownNow();
                    require(owner.awaitTermination(1, TimeUnit.SECONDS), "UNIX_OWNER_TERMINATION_REQUIRED");
                }
            }
            require(!acceptedDescriptor.valid(), "UNIX_ACCEPTED_CLOSE_REQUIRED");
        } finally { client.shutdown(); client.close(); }
    }

    private static void wrongPidSendsNoBytes(TorSocketEndpoint endpoint, LocalServerSocket server) throws Exception {
        // The real peer stays the instrumentation process. Only the expected PID
        // is wrong; this is not a cross-process/cross-UID impersonation fixture.
        OnionApiTransport wrong = new OnionApiTransport(
                new TorSocketEndpoint(endpoint.path, endpoint.uid, endpoint.pid + 1), () -> true, 1000);
        String origin = "http://" + new String(new char[56]).replace('\0', 'a') + ".onion";
        OnionApiTransport.Result result = wrong.execute(new OnionApiRequest(origin, "GET", "/v1/health", new byte[0], "", "", ""));
        require(result.error == OnionApiTransport.Error.NETWORK, "UNIX_WRONG_PID_MUST_FAIL");
        FileDescriptor rejectedDescriptor;
        try (LocalSocket rejected = acceptBounded(server)) {
            rejectedDescriptor = rejected.getFileDescriptor();
            rejected.setSoTimeout(FIXTURE_TIMEOUT_MS);
            require(rejected.getInputStream().read() == -1, "UNIX_WRONG_PID_SENT_BYTES");
        }
        require(!rejectedDescriptor.valid(), "UNIX_REJECTED_CLOSE_REQUIRED");
    }

    private static void backlogDeadline(TorSocketEndpoint endpoint, FileDescriptor listener) throws Exception {
        // Linux AF_UNIX backlog 0 admits one queued connection. No accept runs
        // concurrently, so the following production connect must time out.
        Os.listen(listener, 0);
        FileDescriptor queuedDescriptor;
        try (LocalSocket queued = new LocalSocket()) {
            queued.getInputStream(); // API26 setSoTimeout does not lazily create the FD.
            queuedDescriptor = queued.getFileDescriptor();
            queued.setSoTimeout(FIXTURE_TIMEOUT_MS);
            queued.connect(new LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM));
            AndroidTorSocket blocked = new AndroidTorSocket(endpoint);
            long began = System.nanoTime();
            try { expectRejected(() -> blocked.connect(150)); }
            finally { blocked.shutdown(); blocked.close(); }
            long elapsed = elapsedMillis(began);
            require(elapsed >= 75 && elapsed < 1500, "UNIX_CONNECT_DEADLINE_REQUIRED");
        }
        require(!queuedDescriptor.valid(), "UNIX_QUEUED_CLOSE_REQUIRED");
    }

    private static LocalSocket acceptBounded(LocalServerSocket server) throws Exception {
        StructPollfd pending = new StructPollfd();
        pending.fd = server.getFileDescriptor();
        pending.events = (short) OsConstants.POLLIN;
        require(Os.poll(new StructPollfd[]{pending}, FIXTURE_TIMEOUT_MS) == 1
                && (pending.revents & OsConstants.POLLIN) != 0, "UNIX_ACCEPT_DEADLINE_REQUIRED");
        // Only this thread accepts. The listener owner also sets SO_RCVTIMEO,
        // bounding accept itself if readiness changes between poll and accept.
        return server.accept();
    }

    private static long elapsedMillis(long began) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began); }
    private static void requireAbsent(String path, String code) throws Exception {
        try { Os.lstat(path); throw new AssertionError(code); }
        catch (ErrnoException absent) { if (absent.errno != OsConstants.ENOENT) throw absent; }
    }
    private interface Operation { void run() throws Exception; }
    private static void expectRejected(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("UNIX_REJECTION_REQUIRED"); }
        catch (IOException expected) { }
    }
    private static void require(boolean condition, String code) { if (!condition) throw new AssertionError(code); }
}
