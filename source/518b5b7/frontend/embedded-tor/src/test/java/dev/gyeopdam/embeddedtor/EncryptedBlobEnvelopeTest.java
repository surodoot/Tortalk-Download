package dev.gyeopdam.embeddedtor;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Before;
import org.junit.Test;

/** Host-only JCA tests. Synthetic random keys exist in process memory only, never on disk. */
public final class EncryptedBlobEnvelopeTest {
    private static final String DOMAIN = "TorTalk/KeystoreBlob/v1/dev.gyeopdam.embeddedtor/session";
    private SecretKey key;
    private final byte[] message = "SYNTHETIC_SESSION_TOKEN_NOT_A_REAL_CREDENTIAL".getBytes(StandardCharsets.US_ASCII);

    @Before public void ephemeralKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        key = generator.generateKey();
    }

    @Test public void roundTripsExactBytes() throws Exception {
        byte[] envelope = encrypt(message);
        assertArrayEquals(message, decrypt(envelope));
        assertEquals(message.length + EncryptedBlobEnvelope.OVERHEAD_BYTES, envelope.length);
    }

    @Test public void providerIvChangesEveryWrite() throws Exception {
        byte[] first = encrypt(message);
        byte[] second = encrypt(message);
        assertFalse(Arrays.equals(first, second));
        assertFalse(Arrays.equals(Arrays.copyOfRange(first, 12, 24), Arrays.copyOfRange(second, 12, 24)));
    }

    @Test public void plaintextIsNotWrittenIntoEnvelope() throws Exception {
        String envelope = new String(encrypt(message), StandardCharsets.ISO_8859_1);
        assertFalse(envelope.contains(new String(message, StandardCharsets.US_ASCII)));
    }

    @Test public void everySingleByteMutationFailsClosed() throws Exception {
        byte[] envelope = encrypt(message);
        for (int i = 0; i < envelope.length; i++) {
            byte[] tampered = envelope.clone();
            tampered[i] ^= 1;
            reject(() -> decrypt(tampered), "CORRUPT_STATE");
        }
    }

    @Test public void everyTruncationFailsClosed() throws Exception {
        byte[] envelope = encrypt(message);
        for (int i = 0; i < envelope.length; i++) {
            byte[] truncated = Arrays.copyOf(envelope, i);
            reject(() -> decrypt(truncated), "CORRUPT_STATE");
        }
    }

    @Test public void trailingBytesRejected() throws Exception {
        byte[] envelope = encrypt(message);
        reject(() -> decrypt(Arrays.copyOf(envelope, envelope.length + 1)), "CORRUPT_STATE");
    }

    @Test public void unsupportedVersionRejected() throws Exception {
        byte[] envelope = encrypt(message);
        ByteBuffer.wrap(envelope).putInt(4, 2);
        reject(() -> decrypt(envelope), "CORRUPT_STATE");
    }

    @Test public void negativeLengthRejectedWithoutAllocation() throws Exception {
        byte[] envelope = encrypt(message);
        ByteBuffer.wrap(envelope).putInt(8, -1);
        reject(() -> decrypt(envelope), "CORRUPT_STATE");
    }

    @Test public void overflowingLengthRejectedWithoutAllocation() throws Exception {
        byte[] envelope = encrypt(message);
        ByteBuffer.wrap(envelope).putInt(8, Integer.MAX_VALUE);
        reject(() -> decrypt(envelope), "CORRUPT_STATE");
    }

    @Test public void changedNamespaceCannotDecrypt() throws Exception {
        byte[] envelope = encrypt(message);
        reject(() -> EncryptedBlobEnvelope.decrypt(key, DOMAIN.replace("session", "signal"), envelope, 1024), "CORRUPT_STATE");
    }

    @Test public void changedApplicationCannotDecrypt() throws Exception {
        byte[] envelope = encrypt(message);
        reject(() -> EncryptedBlobEnvelope.decrypt(key, DOMAIN.replace("embeddedtor", "otherapp"), envelope, 1024), "CORRUPT_STATE");
    }

    @Test public void changedKeyCannotDecrypt() throws Exception {
        byte[] envelope = encrypt(message);
        ephemeralKey();
        reject(() -> decrypt(envelope), "CORRUPT_STATE");
    }

    @Test public void nullKeyCannotDecrypt() throws Exception {
        byte[] envelope = encrypt(message);
        reject(() -> EncryptedBlobEnvelope.decrypt(null, DOMAIN, envelope, 1024), "KEY_UNAVAILABLE");
    }

    @Test public void nullEnvelopeIsNotAnEmptyRecord() throws Exception {
        reject(() -> decrypt(null), "CORRUPT_STATE");
    }

    @Test public void boundedPayloadAcceptedAtExactLimit() throws Exception {
        byte[] plaintext = new byte[4096];
        new Random(3).nextBytes(plaintext);
        byte[] envelope = EncryptedBlobEnvelope.encrypt(key, DOMAIN, plaintext, 4096);
        assertArrayEquals(plaintext, EncryptedBlobEnvelope.decrypt(key, DOMAIN, envelope, 4096));
        reject(() -> EncryptedBlobEnvelope.decrypt(key, DOMAIN, envelope, 4095), "CORRUPT_STATE");
    }

    @Test public void tooLargePlaintextRejected() throws Exception {
        reject(() -> EncryptedBlobEnvelope.encrypt(key, DOMAIN, new byte[1025], 1024), "INVALID_INPUT");
    }

    @Test public void invalidLimitsRejected() throws Exception {
        reject(() -> EncryptedBlobEnvelope.encrypt(key, DOMAIN, message, -1), "INVALID_INPUT");
        reject(() -> EncryptedBlobEnvelope.encrypt(key, DOMAIN, message, Integer.MAX_VALUE), "INVALID_INPUT");
    }

    @Test public void emptyPayloadIsAuthenticated() throws Exception {
        byte[] envelope = EncryptedBlobEnvelope.encrypt(key, DOMAIN, new byte[0], 0);
        assertArrayEquals(new byte[0], EncryptedBlobEnvelope.decrypt(key, DOMAIN, envelope, 0));
        assertEquals(EncryptedBlobEnvelope.OVERHEAD_BYTES, envelope.length);
    }

    @Test public void malformedDomainsRejected() throws Exception {
        for (String domain : new String[] { "", "x\ny", "한글", "a".repeat(257) }) {
            reject(() -> EncryptedBlobEnvelope.encrypt(key, domain, message, 1024), "INVALID_INPUT");
        }
        reject(() -> EncryptedBlobEnvelope.encrypt(key, null, message, 1024), "INVALID_INPUT");
    }

    @Test public void randomFramingNeverEscapesAsUncheckedFailure() throws Exception {
        Random random = new Random(4);
        for (int i = 0; i < 300; i++) {
            byte[] malformed = new byte[random.nextInt(150)];
            random.nextBytes(malformed);
            reject(() -> decrypt(malformed), "CORRUPT_STATE");
        }
    }

    @Test public void decryptDoesNotMutateCiphertext() throws Exception {
        byte[] envelope = encrypt(message);
        byte[] before = envelope.clone();
        decrypt(envelope);
        assertArrayEquals(before, envelope);
    }

    private byte[] encrypt(byte[] value) throws Exception { return EncryptedBlobEnvelope.encrypt(key, DOMAIN, value, 1024); }
    private byte[] decrypt(byte[] value) throws Exception { return EncryptedBlobEnvelope.decrypt(key, DOMAIN, value, 1024); }
    private interface Attempt { void run() throws Exception; }
    private static void reject(Attempt attempt, String code) throws Exception {
        try { attempt.run(); fail("Malformed encrypted state accepted"); }
        catch (EncryptedBlobEnvelope.EnvelopeException error) {
            assertEquals(code, error.code);
            assertEquals(code, error.getMessage());
            assertNull(error.getCause());
        }
    }
}
