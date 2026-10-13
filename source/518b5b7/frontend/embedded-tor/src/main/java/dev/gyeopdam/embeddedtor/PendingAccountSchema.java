package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Strict encrypted enrollment payload. Contains no claim that registration reached the server. */
final class PendingAccountSchema {
    static final int MAX_BYTES = 4096;
    private static final int MAGIC = 0x54544531; // TTE1
    private static final int VERSION = 1;
    private static final int FIELDS = 3;
    private static final int[] LIMITS = {69, 45, 51};
    private static final Pattern ACCOUNT = Pattern.compile("demo-account-[0-9a-f]{32}");

    private PendingAccountSchema() { }

    static void validate(String[] fields) {
        if (fields == null || fields.length != FIELDS
                || !SessionSchema.isCanonicalOrigin(fields[0])
                || fields[1] == null || fields[1].length() != 45 || !ACCOUNT.matcher(fields[1]).matches()
                || !SessionSchema.token(fields[2], "gd_acct_")) {
            throw new IllegalArgumentException("INVALID_ENROLLMENT_SCHEMA");
        }
    }

    static byte[] encode(String[] fields) throws IOException {
        try { validate(fields); }
        catch (IllegalArgumentException ignored) { throw failure(); }
        int size = 12 + FIELDS * 2;
        for (String value : fields) size += value.length();
        ByteBuffer output = ByteBuffer.allocate(size).putInt(MAGIC).putInt(VERSION).putInt(FIELDS);
        for (String value : fields) {
            output.putShort((short) value.length());
            for (int i = 0; i < value.length(); i++) output.put((byte) value.charAt(i));
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

    private static IOException failure() { return new IOException("INVALID_ENROLLMENT_SCHEMA"); }
}
