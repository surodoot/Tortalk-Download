package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Bounded, versioned framing around the platform AES-GCM primitive, not a crypto algorithm. */
final class EncryptedBlobEnvelope {
    static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    static final int IV_BYTES = 12;
    static final int TAG_BYTES = 16;
    static final int HEADER_BYTES = 12 + IV_BYTES;
    static final int OVERHEAD_BYTES = HEADER_BYTES + TAG_BYTES;
    private static final int MAGIC = 0x54544231; // TTB1
    private static final int VERSION = 1;

    private EncryptedBlobEnvelope() { }

    static byte[] encrypt(SecretKey key, String domain, byte[] plaintext, int limit)
            throws EnvelopeException {
        checkLimit(limit);
        byte[] aad = domainBytes(domain);
        if (key == null || plaintext == null || plaintext.length > limit) {
            throw new EnvelopeException("INVALID_INPUT");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // The AndroidKeystore provider supplies a fresh randomized IV on every encryption.
            // In particular, do not pass a caller-supplied IV to ENCRYPT_MODE.
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) throw new EnvelopeException("KEY_UNAVAILABLE");
            byte[] header = ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).putInt(VERSION)
                    .putInt(plaintext.length + TAG_BYTES).put(iv).array();
            cipher.updateAAD(aad);
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(plaintext);
            if (encrypted.length != plaintext.length + TAG_BYTES) {
                throw new EnvelopeException("KEY_UNAVAILABLE");
            }
            return ByteBuffer.allocate(header.length + encrypted.length)
                    .put(header).put(encrypted).array();
        } catch (GeneralSecurityException | RuntimeException ignored) {
            // Provider exceptions can contain identifiers; never retain their cause/message.
            throw new EnvelopeException("KEY_UNAVAILABLE");
        }
    }

    static byte[] decrypt(SecretKey key, String domain, byte[] envelope, int limit)
            throws EnvelopeException {
        checkLimit(limit);
        byte[] aad = domainBytes(domain);
        if (key == null) throw new EnvelopeException("KEY_UNAVAILABLE");
        if (envelope == null || envelope.length < OVERHEAD_BYTES
                || envelope.length > limit + OVERHEAD_BYTES) {
            throw new EnvelopeException("CORRUPT_STATE");
        }
        ByteBuffer input = ByteBuffer.wrap(envelope);
        int magic = input.getInt();
        int version = input.getInt();
        int ciphertextBytes = input.getInt();
        if (magic != MAGIC || version != VERSION || ciphertextBytes < TAG_BYTES
                || ciphertextBytes > limit + TAG_BYTES
                || ciphertextBytes != envelope.length - HEADER_BYTES) {
            throw new EnvelopeException("CORRUPT_STATE");
        }
        byte[] iv = new byte[IV_BYTES];
        input.get(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(aad);
            cipher.updateAAD(envelope, 0, HEADER_BYTES);
            byte[] plaintext = cipher.doFinal(envelope, HEADER_BYTES, ciphertextBytes);
            if (plaintext.length != ciphertextBytes - TAG_BYTES) {
                Arrays.fill(plaintext, (byte) 0);
                throw new EnvelopeException("CORRUPT_STATE");
            }
            return plaintext;
        } catch (GeneralSecurityException | RuntimeException ignored) {
            throw new EnvelopeException("CORRUPT_STATE");
        }
    }

    private static void checkLimit(int limit) throws EnvelopeException {
        if (limit < 0 || limit > MAX_PAYLOAD_BYTES) throw new EnvelopeException("INVALID_INPUT");
    }

    private static byte[] domainBytes(String domain) throws EnvelopeException {
        if (domain == null || domain.isEmpty() || domain.length() > 256) {
            throw new EnvelopeException("INVALID_INPUT");
        }
        for (int i = 0; i < domain.length(); i++) {
            char value = domain.charAt(i);
            if (value < 33 || value > 126) throw new EnvelopeException("INVALID_INPUT");
        }
        return domain.getBytes(StandardCharsets.US_ASCII);
    }

    static final class EnvelopeException extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;
        EnvelopeException(String code) { super(code); this.code = code; }
    }
}
