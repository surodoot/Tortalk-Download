package dev.gyeopdam.embeddedtor;

import android.content.Context;
import java.io.IOException;
import java.util.Arrays;

/** Persisted credentials remain a single origin/account/device-bound encrypted record. */
public final class AppSessionStore {
    private static final Object SESSION_LOCK = new Object();
    private final KeystoreBlobStore bytes;

    public AppSessionStore(Context context) { bytes = new KeystoreBlobStore(context, "session"); }

    /** Does not generate a key, log in, contact Tor or restart a session automatically. */
    public Session load() throws StorageException {
        synchronized (SESSION_LOCK) {
            byte[] encoded = null;
            try {
                encoded = bytes.read();
                if (encoded == null) return null;
                String[] fields = SessionSchema.decode(encoded);
                return new Session(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]);
            } catch (KeystoreBlobStore.StorageException error) { throw storageFailure(error); }
            catch (IOException | IllegalArgumentException ignored) { throw failure(Code.CORRUPT_STATE); }
            finally { if (encoded != null) Arrays.fill(encoded, (byte) 0); }
        }
    }

    /** Safe default: recovery capability is excluded even when present in the in-memory Session. */
    public void save(Session session) throws StorageException { save(session, false); }

    /** The caller must pass the user's explicit, default-off recovery-retention choice. */
    public void save(Session session, boolean saveRecovery) throws StorageException {
        if (session == null) throw failure(Code.INVALID_INPUT);
        synchronized (SESSION_LOCK) {
            byte[] encoded = null;
            try {
                encoded = SessionSchema.encode(session.fields(), saveRecovery);
                bytes.write(encoded, previous -> {
                    // Validate old schema and identity under the same OS lock as the commit.
                    // Unsupported schema is never overwritten. A rebind requires explicit clear.
                    if (previous == null) return;
                    String[] old;
                    try { old = SessionSchema.decode(previous); }
                    catch (IOException ignored) {
                        throw new KeystoreBlobStore.StorageException(KeystoreBlobStore.Code.CORRUPT_STATE);
                    }
                    try { SessionSchema.validateReplacement(old, session.fields()); }
                    catch (IOException error) {
                        KeystoreBlobStore.Code code = "ORIGIN_MISMATCH".equals(error.getMessage())
                                ? KeystoreBlobStore.Code.ORIGIN_MISMATCH : KeystoreBlobStore.Code.IDENTITY_MISMATCH;
                        throw new KeystoreBlobStore.StorageException(code);
                    }
                });
            } catch (KeystoreBlobStore.StorageException error) { throw storageFailure(error); }
            catch (IOException ignored) { throw failure(Code.INVALID_INPUT); }
            finally { if (encoded != null) Arrays.fill(encoded, (byte) 0); }
        }
    }

    public void clear() throws StorageException {
        synchronized (SESSION_LOCK) {
            try { bytes.clear(); }
            catch (KeystoreBlobStore.StorageException error) { throw storageFailure(error); }
        }
    }

    public static final class Session {
        public final String serverOnion;
        public final String accountId;
        public final String accountToken;
        public final String deviceId;
        public final String deviceToken;
        public final String sessionToken;

        public Session(String serverOnion, String accountId, String accountToken, String deviceId,
                       String deviceToken, String sessionToken) {
            SessionSchema.validate(new String[] { serverOnion, accountId, accountToken, deviceId, deviceToken, sessionToken });
            this.serverOnion = serverOnion;
            this.accountId = accountId;
            this.accountToken = accountToken;
            this.deviceId = deviceId;
            this.deviceToken = deviceToken;
            this.sessionToken = sessionToken;
        }

        private String[] fields() {
            return new String[] { serverOnion, accountId, accountToken, deviceId, deviceToken, sessionToken };
        }

        @Override public String toString() { return "Session([redacted])"; }
    }

    public enum Code { INVALID_INPUT, CORRUPT_STATE, KEY_UNAVAILABLE, IO_FAILED, ORIGIN_MISMATCH, IDENTITY_MISMATCH }
    public static final class StorageException extends IOException {
        private static final long serialVersionUID = 1L;
        public final Code code;
        private StorageException(Code code) { super(code.name()); this.code = code; }
    }
    private static StorageException storageFailure(KeystoreBlobStore.StorageException error) {
        return failure(Code.valueOf(error.code.name()));
    }
    private static StorageException failure(Code code) { return new StorageException(code); }
    @Override public String toString() { return "AppSessionStore([redacted])"; }
}
