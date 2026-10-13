package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/** Strict six-field, ASCII-only session schema; no Java serialization or permissive JSON parser. */
final class SessionSchema {
    static final int MAX_BYTES = 4096;
    private static final int MAGIC = 0x54545331; // TTS1
    private static final int VERSION = 1;
    private static final int FIELDS = 6;
    private static final int[] LIMITS = { 69, 80, 51, 80, 50, 51 };
    private static final Pattern ORIGIN = Pattern.compile("http://[a-z2-7]{56}\\.onion");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private SessionSchema() { }

    static void validate(String[] fields) {
        if (fields == null || fields.length != FIELDS) invalid();
        for (int i = 0; i < FIELDS; i++) {
            if (fields[i] == null || fields[i].length() > LIMITS[i]) invalid();
        }
        // Canonical port-80 HTTP origin. Address checksum validation belongs to Tor, not a
        // replacement SHA3 implementation (a SHA3 provider is not guaranteed on Android 26).
        boolean noDevice = fields[3].isEmpty() && fields[4].isEmpty();
        if (!isCanonicalOrigin(fields[0])
                || !ID.matcher(fields[1]).matches()
                || (!noDevice && (!ID.matcher(fields[3]).matches() || !token(fields[4], "gd_dev_")))
                || (!fields[2].isEmpty() && !token(fields[2], "gd_acct_"))
                || !token(fields[5], "gd_sess_")) invalid();
    }

    static boolean isCanonicalOrigin(String value) {
        return value != null && value.length() == 69 && ORIGIN.matcher(value).matches();
    }

    static void validateReplacement(String[] old, String[] replacement) throws IOException {
        try { validate(old); validate(replacement); }
        catch (IllegalArgumentException ignored) { throw failure(); }
        if (!old[0].equals(replacement[0])) throw new IOException("ORIGIN_MISMATCH");
        if (!old[1].equals(replacement[1]) || (!old[3].isEmpty() && !old[3].equals(replacement[3]))) {
            throw new IOException("IDENTITY_MISMATCH");
        }
    }

    static byte[] encode(String[] fields) throws IOException { return encode(fields, false); }

    static byte[] encode(String[] fields, boolean saveRecovery) throws IOException {
        try { validate(fields); }
        catch (IllegalArgumentException ignored) { throw failure(); }
        int size = 12 + FIELDS * 2;
        for (int i = 0; i < FIELDS; i++) if (i != 2 || saveRecovery) size += fields[i].length();
        ByteBuffer output = ByteBuffer.allocate(size).putInt(MAGIC).putInt(VERSION).putInt(FIELDS);
        for (int i = 0; i < FIELDS; i++) {
            String value = i == 2 && !saveRecovery ? "" : fields[i];
            output.putShort((short) value.length());
            // All accepted values are ASCII. Avoid an additional plaintext token byte array.
            for (int n = 0; n < value.length(); n++) output.put((byte) value.charAt(n));
        }
        return output.array();
    }

    static String[] decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 12 + FIELDS * 2 || bytes.length > MAX_BYTES) throw failure();
        ByteBuffer input = ByteBuffer.wrap(bytes);
        if (input.getInt() != MAGIC || input.getInt() != VERSION || input.getInt() != FIELDS) throw failure();
        String[] fields = new String[FIELDS];
        for (int i = 0; i < FIELDS; i++) {
            if (input.remaining() < 2) throw failure();
            int length = input.getShort() & 0xffff;
            if (length > LIMITS[i] || length > input.remaining()) throw failure();
            byte[] value = new byte[length];
            try {
                input.get(value);
                for (byte b : value) if (b < 33 || b > 126) throw failure();
                fields[i] = new String(value, StandardCharsets.US_ASCII);
            } finally { Arrays.fill(value, (byte) 0); }
        }
        if (input.hasRemaining()) throw failure();
        try { validate(fields); }
        catch (IllegalArgumentException ignored) { throw failure(); }
        return fields;
    }

    static boolean token(String value, String prefix) {
        if (value == null || value.length() != prefix.length() + 43 || !value.startsWith(prefix)) return false;
        byte[] decoded = null;
        try {
            String encoded = value.substring(prefix.length());
            decoded = Base64.getUrlDecoder().decode(encoded);
            return decoded.length == 32 && Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(decoded).equals(encoded);
        } catch (IllegalArgumentException ignored) { return false; }
        finally { if (decoded != null) Arrays.fill(decoded, (byte) 0); }
    }

    private static void invalid() { throw new IllegalArgumentException("INVALID_SESSION_SCHEMA"); }
    private static IOException failure() { return new IOException("INVALID_SESSION_SCHEMA"); }
}
