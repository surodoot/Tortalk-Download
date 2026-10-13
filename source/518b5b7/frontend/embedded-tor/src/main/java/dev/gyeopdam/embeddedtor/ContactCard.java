package dev.gyeopdam.embeddedtor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Public, origin-bound contact data only. A card is an identity assertion, not
 * proof of ownership: callers must show and confirm it before pinning a peer.
 * No admission, session, device-authentication or account-recovery token belongs here.
 */
public final class ContactCard {
    public static final int MAX_PAYLOAD_CHARS = 1024;
    public static final String VERSION = "tortalk-contact-v1";

    public final String serverOnion;
    public final String accountId;
    public final String deviceId;
    public final int protocolDeviceId;
    public final String identityKeyBase64;
    /** SHA-256 of raw32, excluding the serialized 05 prefix, like SignalJson.keyDigest. */
    public final String keyDigest;

    private ContactCard(String origin, String account, String device, int protocolDevice,
                        String identity, byte[] serializedIdentity) {
        serverOnion = origin;
        accountId = account;
        deviceId = device;
        protocolDeviceId = protocolDevice;
        identityKeyBase64 = identity;
        keyDigest = digest(serializedIdentity);
    }

    public static ContactCard create(String serverOnion, String accountId, String deviceId,
                                     int protocolDeviceId, String identityKeyBase64) {
        validateOrigin(serverOnion);
        if (accountId == null || accountId.length() > 80 || !UiCommandPolicy.validOpaqueId(accountId)
                || deviceId == null || deviceId.length() > 80 || !UiCommandPolicy.validOpaqueId(deviceId)
                || protocolDeviceId < 1 || protocolDeviceId > 127
                || identityKeyBase64 == null || identityKeyBase64.length() != 44) {
            throw invalid();
        }
        byte[] serializedIdentity;
        try {
            serializedIdentity = Base64.getDecoder().decode(identityKeyBase64);
        } catch (IllegalArgumentException rejected) {
            throw invalid();
        }
        if (serializedIdentity.length != 33 || serializedIdentity[0] != 5
                || !Base64.getEncoder().encodeToString(serializedIdentity).equals(identityKeyBase64)) {
            throw invalid();
        }
        return new ContactCard(serverOnion, accountId, deviceId, protocolDeviceId,
                identityKeyBase64, serializedIdentity);
    }

    /** Six exact ASCII fields separated by LF, with no trailing LF or extension fields. */
    public String encode() {
        return VERSION + '\n' + serverOnion + '\n' + accountId + '\n' + deviceId + '\n'
                + protocolDeviceId + '\n' + identityKeyBase64;
    }

    /** The configured origin must be canonical and match exactly; cards never reconfigure it. */
    public static ContactCard decode(String payload, String expectedOrigin) {
        validateOrigin(expectedOrigin);
        if (payload == null || payload.length() > MAX_PAYLOAD_CHARS) throw invalid();
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c != '\n' && (c < 32 || c > 126)) throw invalid();
        }
        String[] fields = payload.split("\n", -1);
        if (fields.length != 6 || !VERSION.equals(fields[0])
                || !fields[4].matches("[1-9][0-9]{0,2}")) throw invalid();
        ContactCard card = create(fields[1], fields[2], fields[3], Integer.parseInt(fields[4]), fields[5]);
        if (!expectedOrigin.equals(card.serverOnion)) {
            throw new IllegalArgumentException("CONTACT_ORIGIN_MISMATCH");
        }
        return card;
    }

    private static void validateOrigin(String origin) {
        // Reuse the transport's origin policy, while refusing its slash/:80 aliases.
        // As in that policy, onion checksum validation belongs to Tor, not a custom crypto implementation.
        if (origin == null || origin.length() != 69) throw invalid();
        try {
            if (!OnionApiRequest.canonicalOrigin(origin).equals(origin)) throw invalid();
        } catch (IllegalArgumentException rejected) {
            throw invalid();
        }
    }

    private static String digest(byte[] serializedIdentity) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(Arrays.copyOfRange(serializedIdentity, 1, serializedIdentity.length));
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(64);
            for (byte b : hash) result.append(hex[(b & 255) >>> 4]).append(hex[b & 15]);
            return result.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("CONTACT_DIGEST_UNAVAILABLE");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONTACT_INVALID");
    }

    @Override public String toString() { return "ContactCard([redacted])"; }
}
