package dev.gyeopdam.embeddedtor;

import android.content.Context;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Crash-safe credentials for an explicitly requested, idempotent account-registration attempt.
 * Always run on a worker thread. Persist successfully before sending POST /v1/accounts.
 * Loading pending credentials does not mean that the server has accepted the registration.
 */
public final class PendingAccountStore {
    private final KeystoreBlobStore bytes;

    public PendingAccountStore(Context context) { bytes = new KeystoreBlobStore(context, "enrollment"); }

    /** Read-only: never creates credentials or a Keystore key. */
    public Pending load() throws StorageException {
        byte[] encoded = null;
        try {
            encoded = bytes.read();
            return encoded == null ? null : decode(encoded);
        } catch (KeystoreBlobStore.StorageException error) { throw storageFailure(error); }
        finally { if (encoded != null) Arrays.fill(encoded, (byte) 0); }
    }

    /** Call only following explicit signup. Repeated calls return the same durable credentials. */
    public Pending loadOrCreate(String canonicalOrigin) throws StorageException {
        if (!SessionSchema.isCanonicalOrigin(canonicalOrigin)) throw failure(Code.INVALID_INPUT);
        byte[] encoded = null;
        try {
            encoded = bytes.readOrCreate(() -> {
                // Runs on the device only, inside the storage lock, after absence is verified.
                byte[] idBytes = new byte[16];
                byte[] secret = new byte[32];
                try {
                    SecureRandom random = new SecureRandom();
                    random.nextBytes(idBytes);
                    random.nextBytes(secret);
                    char[] hex = new char[idBytes.length * 2];
                    final String alphabet = "0123456789abcdef";
                    for (int i = 0; i < idBytes.length; i++) {
                        hex[2 * i] = alphabet.charAt((idBytes[i] & 0xff) >>> 4);
                        hex[2 * i + 1] = alphabet.charAt(idBytes[i] & 15);
                    }
                    return PendingAccountSchema.encode(new String[] {canonicalOrigin,
                            "demo-account-" + new String(hex),
                            "gd_acct_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret)});
                } catch (IOException | RuntimeException ignored) {
                    throw new KeystoreBlobStore.StorageException(KeystoreBlobStore.Code.IO_FAILED);
                } finally { Arrays.fill(idBytes, (byte) 0); Arrays.fill(secret, (byte) 0); }
            });
            Pending pending = decode(encoded);
            if (!pending.serverOnion.equals(canonicalOrigin)) throw failure(Code.ORIGIN_MISMATCH);
            return pending;
        } catch (KeystoreBlobStore.StorageException error) { throw storageFailure(error); }
        finally { if (encoded != null) Arrays.fill(encoded, (byte) 0); }
    }

    /** User-confirmed abandonment only; clearing pending credentials can lose account recovery. */
    public void clear() throws StorageException {
        try { bytes.clear(); }
        catch (KeystoreBlobStore.StorageException error) { throw storageFailure(error); }
    }

    /** Call after a successful account flow and the user's confirmation that recovery is recorded. */
    public void acknowledge() throws StorageException { clear(); }

    private static Pending decode(byte[] encoded) throws StorageException {
        try {
            String[] fields = PendingAccountSchema.decode(encoded);
            return new Pending(fields[0], fields[1], fields[2]);
        } catch (IOException | IllegalArgumentException ignored) { throw failure(Code.CORRUPT_STATE); }
    }

    public static final class Pending {
        public final String serverOnion;
        public final String accountId;
        public final String accountToken;

        public Pending(String serverOnion, String accountId, String accountToken) {
            PendingAccountSchema.validate(new String[] {serverOnion, accountId, accountToken});
            this.serverOnion = serverOnion;
            this.accountId = accountId;
            this.accountToken = accountToken;
        }

        @Override public String toString() { return "Pending([redacted])"; }
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
    @Override public String toString() { return "PendingAccountStore([redacted])"; }
}
