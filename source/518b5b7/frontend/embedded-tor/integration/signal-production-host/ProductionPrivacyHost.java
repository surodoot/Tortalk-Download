package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.util.Base64;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider;

/** Synthetic in-memory fixture. No logger/provider is installed or initialized. */
public final class ProductionPrivacyHost {
    private static final String A = "demo-privacy-canary-account-alice";
    private static final String B = "demo-privacy-canary-account-bob";
    private static final String AD = "demo-privacy-canary-device-alice";
    private static final String BD = "demo-privacy-canary-device-bob";
    private static final String ROOM = "demo-privacy-canary-room";
    private static final String TEXT = "demo-privacy-canary-plaintext-6490f878";
    private static final String ORIGIN = "http://" + "a".repeat(56) + ".onion";

    private static void check(boolean value) {
        if (!value) throw new AssertionError("HOST_PRIVACY_ASSERTION");
    }
    private static final class Memory implements SignalClient.StateStorage {
        byte[] state;
        boolean fail;
        public byte[] load() { return state == null ? null : state.clone(); }
        public void save(byte[] bytes) throws IOException {
            if (fail) throw new IOException("demo-privacy-canary-storage-error");
            state = bytes.clone();
        }
    }
    interface Op { void run() throws Exception; }
    private static void rejects(Op op) throws Exception {
        boolean rejected = false;
        try { op.run(); } catch (Exception expected) { rejected = true; }
        check(rejected);
    }

    public static int[] run() throws Exception {
        int groups = 0;
        check(SignalProtocolLoggerProvider.getProvider() == null);
        Memory am = new Memory(), bm = new Memory();
        SignalClient a = new SignalClient(am), b = new SignalClient(bm);
        a.initialize(ORIGIN, A, AD, 3);
        b.initialize(ORIGIN, B, BD, 3);
        check(a.prepareDeviceToken().startsWith("gd_dev_"));
        groups++; // Initialization and capability generation.

        a.acceptPeer(ROOM, B, BD, b.protocolDeviceId(), b.identityKeyBase64());
        b.acceptPeer(ROOM, A, AD, a.protocolDeviceId(), a.identityKeyBase64());
        a.acceptBundle(ROOM, B, BD, b.publication().bundle(0));
        SignalClient.Envelope first = a.stageText(ROOM, B, BD, "demo-privacy-canary-message-one", TEXT);
        byte[] badBytes = Base64.getDecoder().decode(first.bodyBase64);
        badBytes[badBytes.length - 1] ^= 1;
        SignalClient.Envelope bad = new SignalClient.Envelope(first.roomId, first.messageId,
            first.senderAccountId, first.senderDeviceId, first.recipientAccountId,
            first.recipientDeviceId, first.type, Base64.getEncoder().encodeToString(badBytes));
        rejects(() -> b.receive(bad));
        groups++; // Tamper rejection before accepting the original.
        check(TEXT.equals(b.receive(first).text));
        groups++; // First prekey session and message.
        check(b.receive(first).duplicate);
        groups++; // Replay/duplicate detection.

        SignalClient.Envelope reply = b.stageText(ROOM, A, AD, "demo-privacy-canary-reply", TEXT + "reply");
        check(a.receive(reply).text.equals(TEXT + "reply"));
        groups++; // Reverse direction.
        SignalClient.Envelope next = a.stageText(ROOM, B, BD, "demo-privacy-canary-next", TEXT + "next");
        SignalClient.Envelope later = a.stageText(ROOM, B, BD, "demo-privacy-canary-later", TEXT + "later");
        check(b.receive(later).text.equals(TEXT + "later"));
        check(b.receive(next).text.equals(TEXT + "next"));
        groups++; // Out-of-order ratchet messages.

        am.fail = true;
        rejects(() -> a.stageText(ROOM, B, BD, "demo-privacy-canary-storage", TEXT));
        am.fail = false;
        groups++; // Persistent-state failure is contained.
        SignalClient replacement = new SignalClient(new Memory());
        replacement.initialize(ORIGIN, B, BD, 1);
        rejects(() -> a.acceptPeer(ROOM, B, BD, b.protocolDeviceId(), replacement.identityKeyBase64()));
        groups++; // Pinned peer identity cannot be replaced.
        check(SignalProtocolLoggerProvider.getProvider() == null);

        Result regression = new JUnitCore().run(SignalClientTest.class);
        check(regression.wasSuccessful());
        check(regression.getRunCount() == 32 && regression.getIgnoreCount() == 0);
        check(SignalProtocolLoggerProvider.getProvider() == null);
        return new int[]{groups, regression.getRunCount()};
    }
}
