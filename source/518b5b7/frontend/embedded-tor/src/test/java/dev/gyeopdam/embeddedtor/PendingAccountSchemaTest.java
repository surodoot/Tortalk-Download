package dev.gyeopdam.embeddedtor;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;

/** Pure schema only: no Android calls, real account registration, or persistent key generation. */
public final class PendingAccountSchemaTest {
    private static final String ORIGIN = "http://xao2lxsmia2edq2n5zxg6uahx6xox2t7bfjw6b5vdzsxi7ezmqob6qid.onion";

    @Test public void roundTripPreservesExactRetryCredentials() throws Exception {
        String[] original = fields();
        byte[] first = PendingAccountSchema.encode(original);
        String[] resumed = PendingAccountSchema.decode(first);
        assertArrayEquals(original, resumed);
        assertArrayEquals(first, PendingAccountSchema.encode(resumed));
    }

    @Test public void recoveryCapabilityIsRequiredUntilExplicitAcknowledgement() throws Exception {
        String[] value = fields(); value[2] = ""; rejectEncode(value);
        value = fields(); value[2] = null; rejectEncode(value);
    }

    @Test public void sessionSchemaCannotMasqueradeAsEnrollment() throws Exception {
        String[] value = fields();
        byte[] session = SessionSchema.encode(new String[] {value[0], value[1], value[2], "", "",
                "gd_sess_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])});
        rejectDecode(session);
    }

    @Test public void enrollmentCannotMasqueradeAsSession() throws Exception {
        try { SessionSchema.decode(PendingAccountSchema.encode(fields())); fail("Wrong schema accepted"); }
        catch (IOException error) { assertEquals("INVALID_SESSION_SCHEMA", error.getMessage()); }
    }

    @Test public void accountIdentifierRequiresExactPrefixAnd128BitLowercaseHex() throws Exception {
        for (String id : new String[] { "demo-account-a", "account-" + "a".repeat(32), "demo-account-" + "a".repeat(31),
                "demo-account-" + "a".repeat(33), "demo-account-" + "A".repeat(32), "demo-account-" + "g".repeat(32),
                "demo-account-" + "a".repeat(31) + "\n", "" }) {
            String[] value = fields(); value[1] = id; rejectEncode(value);
        }
    }

    @Test public void allFieldsRequiredAndBounded() throws Exception {
        for (int index = 0; index < 3; index++) {
            String[] value = fields(); value[index] = null; rejectEncode(value);
            value = fields(); value[index] = ""; rejectEncode(value);
            value = fields(); value[index] = "x".repeat(5000); rejectEncode(value);
        }
    }

    @Test public void originMustAlreadyBeCanonicalOnionPort80() throws Exception {
        for (String origin : new String[] { ORIGIN + "/", ORIGIN + ":80", ORIGIN + ":443", "https://example.com",
                ORIGIN.replace("http://", "https://"), ORIGIN.toUpperCase(), ORIGIN + "\n" }) {
            String[] value = fields(); value[0] = origin; rejectEncode(value);
        }
    }

    @Test public void tokenRequiresCorrectCapabilityTypeAndCanonical256Bits() throws Exception {
        for (String token : new String[] { "gd_sess_" + "A".repeat(43), "gd_acct_" + "A".repeat(42),
                "gd_acct_" + "A".repeat(44), "gd_acct_" + "A".repeat(42) + "B", "gd_acct_" + "A".repeat(43) + "=" }) {
            String[] value = fields(); value[2] = token; rejectEncode(value);
        }
    }

    @Test public void unknownVersionAndFieldCountRejected() throws Exception {
        byte[] encoded = PendingAccountSchema.encode(fields()); ByteBuffer.wrap(encoded).putInt(4, 2); rejectDecode(encoded);
        for (int count : new int[] { 0, 2, 4, -1, Integer.MAX_VALUE }) {
            encoded = PendingAccountSchema.encode(fields()); ByteBuffer.wrap(encoded).putInt(8, count); rejectDecode(encoded);
        }
    }

    @Test public void allTruncatedRecordsRejected() throws Exception {
        byte[] encoded = PendingAccountSchema.encode(fields());
        for (int size = 0; size < encoded.length; size++) rejectDecode(Arrays.copyOf(encoded, size));
    }

    @Test public void oversizedLengthRejectedBeforeAllocation() throws Exception {
        byte[] encoded = PendingAccountSchema.encode(fields()); ByteBuffer.wrap(encoded).putShort(12, (short) 65535); rejectDecode(encoded);
    }

    @Test public void trailingFieldsAndBytesRejected() throws Exception {
        byte[] encoded = PendingAccountSchema.encode(fields()); rejectDecode(Arrays.copyOf(encoded, encoded.length + 1));
    }

    @Test public void nulAndNonAsciiRejected() throws Exception {
        byte[] encoded = PendingAccountSchema.encode(fields()); encoded[14] = 0; rejectDecode(encoded);
        encoded = PendingAccountSchema.encode(fields()); encoded[14] = (byte) 128; rejectDecode(encoded);
    }

    @Test public void nullOversizedAndUnknownFieldArraysRejected() throws Exception {
        rejectDecode(null); rejectDecode(new byte[PendingAccountSchema.MAX_BYTES + 1]);
        rejectEncode(null); rejectEncode(Arrays.copyOf(fields(), 2)); rejectEncode(Arrays.copyOf(fields(), 4));
    }

    @Test public void pendingObjectNeverPrintsCredentials() {
        String[] fields = fields();
        PendingAccountStore.Pending pending = new PendingAccountStore.Pending(fields[0], fields[1], fields[2]);
        assertEquals("Pending([redacted])", pending.toString());
        assertEquals(fields[0], pending.serverOnion);
        assertEquals(fields[1], pending.accountId);
        assertEquals(fields[2], pending.accountToken);
    }

    private static String[] fields() {
        return new String[] { ORIGIN, "demo-account-" + "0".repeat(32),
                "gd_acct_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]) };
    }
    private static void rejectEncode(String[] value) throws Exception {
        try { PendingAccountSchema.encode(value); fail("Invalid enrollment accepted"); }
        catch (IOException error) { assertEquals("INVALID_ENROLLMENT_SCHEMA", error.getMessage()); assertNull(error.getCause()); }
    }
    private static void rejectDecode(byte[] encoded) throws Exception {
        try { PendingAccountSchema.decode(encoded); fail("Invalid enrollment accepted"); }
        catch (IOException error) { assertEquals("INVALID_ENROLLMENT_SCHEMA", error.getMessage()); assertNull(error.getCause()); }
    }
}
