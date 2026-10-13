package dev.gyeopdam.embeddedtor;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;

/** Pure schema tests use recognizable synthetic values, not real account credentials. */
public final class SessionSchemaTest {
    private static final String ORIGIN = "http://xao2lxsmia2edq2n5zxg6uahx6xox2t7bfjw6b5vdzsxi7ezmqob6qid.onion";

    @Test public void roundTripsStrictlyWithExplicitRecoveryOptIn() throws Exception {
        assertArrayEquals(fields(), SessionSchema.decode(SessionSchema.encode(fields(), true)));
    }

    @Test public void defaultEncodingNeverPersistsRecoveryCapability() throws Exception {
        String[] before = fields();
        byte[] saved = SessionSchema.encode(before);
        String[] expected = before.clone(); expected[2] = "";
        assertArrayEquals(expected, SessionSchema.decode(saved));
        assertFalse(new String(saved, StandardCharsets.US_ASCII).contains(before[2]));
        assertEquals(fields()[2], before[2]);
    }

    @Test public void falseChoiceRemovesPreviouslyRetainedRecoveryCapability() throws Exception {
        String[] previouslyRetained = SessionSchema.decode(SessionSchema.encode(fields(), true));
        assertFalse(previouslyRetained[2].isEmpty());
        assertEquals("", SessionSchema.decode(SessionSchema.encode(previouslyRetained, false))[2]);
    }

    @Test public void optionalRecoveryMayBeEmptyEvenWhenOptedIn() throws Exception {
        String[] fields = fields(); fields[2] = "";
        assertArrayEquals(fields, SessionSchema.decode(SessionSchema.encode(fields, true)));
    }

    @Test public void canonicalOriginIsStoredWithItsCredentials() throws Exception {
        String[] restored = SessionSchema.decode(SessionSchema.encode(fields()));
        assertEquals(ORIGIN, restored[0]);
        assertEquals(fields()[4], restored[4]);
        assertEquals(fields()[5], restored[5]);
    }

    @Test public void nonCanonicalOrNonOnionOriginsRejected() throws Exception {
        for (String origin : new String[] { ORIGIN + "/", ORIGIN + ":80", ORIGIN + ":81", ORIGIN + "?a=b",
                ORIGIN + "#x", ORIGIN + "/v1", ORIGIN.toUpperCase(), ORIGIN.replace("http://", "https://"),
                ORIGIN.replace("http://", "http://a@"), "http://example.com", "http://127.0.0.1",
                "http://" + "a".repeat(16) + ".onion", " " + ORIGIN, ORIGIN + "\n" }) {
            String[] fields = fields(); fields[0] = origin; rejectEncode(fields);
        }
    }

    @Test public void invalidOnionAlphabetRejected() throws Exception {
        String[] fields = fields(); fields[0] = ORIGIN.replaceFirst("x", "0"); rejectEncode(fields);
    }

    @Test public void everyRequiredFieldRejectsNullAndEmpty() throws Exception {
        for (int i = 0; i < 6; i++) {
            String[] fields = fields(); fields[i] = null; rejectEncode(fields);
            if (i != 2) { fields = fields(); fields[i] = ""; rejectEncode(fields); }
        }
    }

    @Test public void tooManyOrTooFewFieldsRejected() throws Exception {
        rejectEncode(Arrays.copyOf(fields(), 5));
        rejectEncode(Arrays.copyOf(fields(), 7));
        rejectEncode(null);
    }

    @Test public void wrongTokenTypeRejected() throws Exception {
        String[] fields = fields(); fields[5] = fields[2]; rejectEncode(fields);
        fields = fields(); fields[2] = fields[5]; rejectEncode(fields);
        fields = fields(); fields[4] = fields[5]; rejectEncode(fields);
    }

    @Test public void non256BitTokensRejected() throws Exception {
        for (int bytes : new int[] { 0, 16, 31, 33, 64 }) {
            String[] fields = fields(); fields[2] = "gd_acct_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[bytes]);
            rejectEncode(fields);
        }
    }

    @Test public void nonCanonicalBase64LowBitsRejected() throws Exception {
        String[] fields = fields(); fields[2] = "gd_acct_" + "A".repeat(42) + "B";
        rejectEncode(fields);
    }

    @Test public void paddedOrControlCharacterTokensRejected() throws Exception {
        for (String suffix : new String[] { "=", "\n", "\r", " ", "\u0000" }) {
            String[] fields = fields(); fields[2] += suffix; rejectEncode(fields);
        }
    }

    @Test public void identifiersAreBoundedAndAsciiOnly() throws Exception {
        for (String id : new String[] { "a".repeat(81), "hello world", "x\ny", "../path", "한글", "x@y" }) {
            String[] fields = fields(); fields[1] = id; rejectEncode(fields);
            fields = fields(); fields[3] = id; rejectEncode(fields);
        }
        String[] fields = fields(); fields[1] = "a".repeat(80); fields[3] = "b".repeat(80);
        assertArrayEquals(fields, SessionSchema.decode(SessionSchema.encode(fields, true)));
    }

    @Test public void unknownSchemaVersionRejected() throws Exception {
        byte[] encoded = SessionSchema.encode(fields()); ByteBuffer.wrap(encoded).putInt(4, 2); rejectDecode(encoded);
    }

    @Test public void unknownFieldCountRejected() throws Exception {
        for (int count : new int[] { -1, 0, 5, 7, Integer.MAX_VALUE }) {
            byte[] encoded = SessionSchema.encode(fields()); ByteBuffer.wrap(encoded).putInt(8, count); rejectDecode(encoded);
        }
    }

    @Test public void allTruncatedRecordsRejected() throws Exception {
        byte[] encoded = SessionSchema.encode(fields(), true);
        for (int length = 0; length < encoded.length; length++) rejectDecode(Arrays.copyOf(encoded, length));
    }

    @Test public void oversizedFieldsRejectedBeforeAllocation() throws Exception {
        byte[] encoded = SessionSchema.encode(fields()); ByteBuffer.wrap(encoded).putShort(12, (short) 0xffff); rejectDecode(encoded);
    }

    @Test public void trailingFieldsAndBytesRejected() throws Exception {
        byte[] encoded = SessionSchema.encode(fields()); rejectDecode(Arrays.copyOf(encoded, encoded.length + 1));
    }

    @Test public void nonAsciiAndEmbeddedNulRejectedOnDecode() throws Exception {
        byte[] encoded = SessionSchema.encode(fields()); encoded[14] = (byte) 0xff; rejectDecode(encoded);
        encoded = SessionSchema.encode(fields()); encoded[14] = 0; rejectDecode(encoded);
    }

    @Test public void oversizedOrMissingRecordRejected() throws Exception {
        rejectDecode(new byte[SessionSchema.MAX_BYTES + 1]); rejectDecode(null);
    }

    @Test public void sessionObjectDoesNotPrintSecrets() {
        String[] f = fields();
        AppSessionStore.Session value = new AppSessionStore.Session(f[0], f[1], f[2], f[3], f[4], f[5]);
        assertEquals("Session([redacted])", value.toString());
        assertEquals(f[0], value.serverOnion);
        assertEquals(f[5], value.sessionToken);
    }

    @Test public void pairedEmptyDeviceFieldsSupportPreEnrollmentSession() throws Exception {
        String[] fields = fields(); fields[3] = ""; fields[4] = "";
        assertArrayEquals(fields, SessionSchema.decode(SessionSchema.encode(fields, true)));
        new AppSessionStore.Session(fields[0], fields[1], fields[2], "", "", fields[5]);
    }

    @Test public void emptyDeviceStillRequiresSessionToken() throws Exception {
        String[] fields = fields(); fields[3] = ""; fields[4] = ""; fields[5] = "";
        rejectEncode(fields);
    }

    @Test public void initialDeviceBindingAllowedOnce() throws Exception {
        String[] before = fields(); before[3] = ""; before[4] = "";
        SessionSchema.validateReplacement(before, fields());
        SessionSchema.validateReplacement(fields(), fields());
    }

    @Test public void clearingOrChangingBoundDeviceRequiresExplicitReset() throws Exception {
        String[] replacement = fields(); replacement[3] = ""; replacement[4] = "";
        rejectReplacement(fields(), replacement, "IDENTITY_MISMATCH");
        replacement = fields(); replacement[3] = "demo-other-device";
        rejectReplacement(fields(), replacement, "IDENTITY_MISMATCH");
    }

    @Test public void changingAccountOrOriginRequiresExplicitReset() throws Exception {
        String[] replacement = fields(); replacement[1] = "demo-account-b";
        rejectReplacement(fields(), replacement, "IDENTITY_MISMATCH");
        replacement = fields(); replacement[0] = "http://" + "a".repeat(56) + ".onion";
        rejectReplacement(fields(), replacement, "ORIGIN_MISMATCH");
    }

    @Test public void invalidPreviousSchemaCannotBeOverwritten() throws Exception {
        String[] old = fields(); old[5] = "bad";
        rejectReplacement(old, fields(), "INVALID_SESSION_SCHEMA");
    }

    private static String[] fields() {
        String capability = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        return new String[] { ORIGIN, "demo-account-a", "gd_acct_" + capability, "demo-device-a",
                "gd_dev_" + capability, "gd_sess_" + capability };
    }
    private static void rejectEncode(String[] fields) throws Exception {
        try { SessionSchema.encode(fields, true); fail("Invalid schema accepted"); }
        catch (IOException error) { assertEquals("INVALID_SESSION_SCHEMA", error.getMessage()); assertNull(error.getCause()); }
    }
    private static void rejectDecode(byte[] encoded) throws Exception {
        try { SessionSchema.decode(encoded); fail("Invalid schema accepted"); }
        catch (IOException error) { assertEquals("INVALID_SESSION_SCHEMA", error.getMessage()); assertNull(error.getCause()); }
    }
    private static void rejectReplacement(String[] old, String[] replacement, String code) throws Exception {
        try { SessionSchema.validateReplacement(old, replacement); fail("Invalid credential binding accepted"); }
        catch (IOException error) { assertEquals(code, error.getMessage()); assertNull(error.getCause()); }
    }
}
