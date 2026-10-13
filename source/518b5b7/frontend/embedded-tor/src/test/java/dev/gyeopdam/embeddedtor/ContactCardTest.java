package dev.gyeopdam.embeddedtor;

import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ContactCardTest {
    static final String ORIGIN = "http://" + "a".repeat(56) + ".onion";
    static final String OTHER_ORIGIN = "http://" + "b".repeat(56) + ".onion";
    // Synthetic serialized identity: 05 followed by raw bytes 00..1f; never a live key.
    static final String IDENTITY = "BQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f";
    static final String DIGEST = "630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd";

    static ContactCard card() {
        return ContactCard.create(ORIGIN, "account-synthetic", "device-synthetic", 1, IDENTITY);
    }

    static String withField(int index, String value) {
        String[] fields = card().encode().split("\n", -1);
        fields[index] = value;
        return String.join("\n", fields);
    }

    static void rejects(String message, Runnable action) {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals(message, rejected.getMessage());
        assertNull(rejected.getCause());
    }

    @Test public void publicFieldsRoundTripExactly() {
        ContactCard original = card();
        ContactCard decoded = ContactCard.decode(original.encode(), ORIGIN);
        assertEquals(original.encode(), decoded.encode());
        assertEquals(ORIGIN, decoded.serverOnion);
        assertEquals("account-synthetic", decoded.accountId);
        assertEquals("device-synthetic", decoded.deviceId);
        assertEquals(1, decoded.protocolDeviceId);
        assertEquals(IDENTITY, decoded.identityKeyBase64);
        assertEquals(6, decoded.encode().split("\n", -1).length);
        assertFalse(decoded.encode().endsWith("\n"));
        assertTrue(decoded.encode().length() < ContactCard.MAX_PAYLOAD_CHARS);
    }

    @Test public void fingerprintMatchesKnownRaw32DigestAndProductionSignalJson() throws Exception {
        assertEquals(DIGEST, card().keyDigest);
        assertEquals(SignalJson.keyDigest(IDENTITY), card().keyDigest);
        assertFalse(java.util.Arrays.equals(
                java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(IDENTITY)),
                java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOfRange(Base64.getDecoder().decode(IDENTITY), 1, 33))));
    }

    @Test public void differentCanonicalOriginIsRejected() {
        rejects("CONTACT_ORIGIN_MISMATCH", () -> ContactCard.decode(card().encode(), OTHER_ORIGIN));
    }

    @Test public void originAliasesUrlsAndCredentialsAreRejected() {
        for (String origin : new String[]{ORIGIN + "/", ORIGIN + ":80", ORIGIN + "/v1/contact",
                ORIGIN + "?token=private", ORIGIN + "#private", ORIGIN.replace("http:", "https:"),
                ORIGIN.replace("http://", "http://user:password@"), "https://example.org",
                "http://localhost", "http://127.0.0.1", "http://" + "a".repeat(16) + ".onion",
                ORIGIN.toUpperCase(java.util.Locale.ROOT), "http://" + "0".repeat(56) + ".onion", "", null}) {
            rejects("CONTACT_INVALID", () -> ContactCard.decode(withField(1, String.valueOf(origin)), ORIGIN));
            rejects("CONTACT_INVALID", () -> ContactCard.decode(card().encode(), origin));
        }
    }

    @Test public void missingExtraReorderedAndCredentialFieldsAreRejected() {
        String[] fields = card().encode().split("\n", -1);
        for (String payload : new String[]{"", ContactCard.VERSION, card().encode() + "\n",
                card().encode() + "\ntt_session_synthetic-secret", card().encode() + "\naccount_token=private",
                card().encode() + "\nsession_token=private", card().encode() + "\ndevice_token=private",
                card().encode() + "\nregistration_token=private", card().encode() + "\nhttps://example.org",
                String.join("\n", fields[0], fields[2], fields[1], fields[3], fields[4], fields[5]),
                String.join("\n", fields[0], fields[1], fields[2], fields[3], fields[4]),
                "{\"account_id\":\"account-synthetic\",\"session_token\":\"private\"}"}) {
            rejects("CONTACT_INVALID", () -> ContactCard.decode(payload, ORIGIN));
        }
    }

    @Test public void exactVersionAndAsciiLineEndingsAreRequired() {
        for (String payload : new String[]{withField(0, "tortalk-contact-v2"), withField(0, "TORTALK-CONTACT-V1"),
                " " + card().encode(), card().encode() + " ", card().encode().replace("\n", "\r\n"),
                withField(2, "account-\tbad"), withField(2, "account-\u0000bad"),
                withField(2, "account-\u007fbad"), withField(2, "account-\u00e9bad"),
                "\ufeff" + card().encode(), withField(2, "account-\u202ebad")}) {
            rejects("CONTACT_INVALID", () -> ContactCard.decode(payload, ORIGIN));
        }
    }

    @Test public void publicIdsReuseUiPolicyWithoutPathOrTokenSyntax() {
        for (String id : new String[]{"", "short", "Uppercase", "_leading", "a".repeat(81),
                "account/other", "account?x=1", "account#secret", "../private", "<script>",
                "tt_session_synthetic", "tt_device_synthetic", "tt_join_synthetic", "Bearer private"}) {
            assertFalse(UiCommandPolicy.validOpaqueId(id));
            rejects("CONTACT_INVALID", () -> ContactCard.decode(withField(2, id), ORIGIN));
            rejects("CONTACT_INVALID", () -> ContactCard.decode(withField(3, id), ORIGIN));
        }
        ContactCard max = ContactCard.create(ORIGIN, "a".repeat(80), "b".repeat(80), 127, IDENTITY);
        assertEquals(max.encode(), ContactCard.decode(max.encode(), ORIGIN).encode());
    }

    @Test public void protocolDeviceIdIsCanonicalDecimalWithinSignalRange() {
        for (String value : new String[]{"0", "-1", "128", "999", "2147483648", "01", "+1", "1.0", "1e0", " 1", "1 ", ""}) {
            rejects("CONTACT_INVALID", () -> ContactCard.decode(withField(4, value), ORIGIN));
        }
        assertEquals(127, ContactCard.decode(withField(4, "127"), ORIGIN).protocolDeviceId);
        for (int value : new int[]{Integer.MIN_VALUE, -1, 0, 128, Integer.MAX_VALUE}) {
            rejects("CONTACT_INVALID", () -> ContactCard.create(ORIGIN, "account-synthetic", "device-synthetic", value, IDENTITY));
        }
    }

    @Test public void serializedIdentityMustBeCanonicalBase64OfExactly05And32Bytes() {
        byte[] wrongPrefix = Base64.getDecoder().decode(IDENTITY);
        wrongPrefix[0] = 4;
        for (String value : new String[]{"", IDENTITY + "=", IDENTITY + "\n", IDENTITY + " ",
                IDENTITY.substring(1), Base64.getEncoder().encodeToString(wrongPrefix),
                Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(new byte[34]),
                "tt_device_synthetic-private-token", "A".repeat(44), "_".repeat(44), "-".repeat(44)}) {
            rejects("CONTACT_INVALID", () -> ContactCard.decode(withField(5, value), ORIGIN));
        }
        rejects("CONTACT_INVALID", () -> ContactCard.create(ORIGIN, "account-synthetic", "device-synthetic", 1, null));
    }

    @Test public void payloadBoundsAreCheckedBeforeParsing() {
        rejects("CONTACT_INVALID", () -> ContactCard.decode(null, ORIGIN));
        rejects("CONTACT_INVALID", () -> ContactCard.decode("a".repeat(1025), ORIGIN));
        rejects("CONTACT_INVALID", () -> ContactCard.decode("\n".repeat(1024), ORIGIN));
        rejects("CONTACT_INVALID", () -> ContactCard.decode("a".repeat(1_000_000), ORIGIN));
    }

    @Test public void logsDoNotIncludeCardFields() {
        assertEquals("ContactCard([redacted])", card().toString());
    }
}
