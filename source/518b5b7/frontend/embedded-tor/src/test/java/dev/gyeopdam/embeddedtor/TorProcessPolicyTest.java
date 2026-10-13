package dev.gyeopdam.embeddedtor;

import org.junit.Test;
import static org.junit.Assert.*;

public class TorProcessPolicyTest {
    @Test public void unclaimedIncarnationExpiresEvenWithoutBinderConnection() {
        TorProcessPolicy policy = new TorProcessPolicy(100);
        assertEquals(TorProcessPolicy.Expiry.NONE, policy.expiry(5099));
        assertEquals(TorProcessPolicy.Expiry.STARTUP, policy.expiry(5100));
        assertFalse(policy.claim(1, 1, 5100, 5100));
    }

    @Test public void validClaimRequiresFreshSenderTimeAndPositiveIdentifiers() {
        assertFalse(new TorProcessPolicy(0).claim(0, 1, 0, 0));
        assertFalse(new TorProcessPolicy(0).claim(1, 0, 0, 0));
        assertFalse(new TorProcessPolicy(0).claim(1, 1, -1, 0));
        assertFalse(new TorProcessPolicy(0).claim(1, 1, 101, 100));
        assertFalse(new TorProcessPolicy(0).claim(1, 1, 0, 4000));
        assertTrue(new TorProcessPolicy(0).claim(1, 1, 0, 3999));
    }

    @Test public void queuedHeartbeatUsesSendTimeRatherThanReceiveTime() {
        TorProcessPolicy policy = active(0);
        assertTrue(policy.renew(1, 2, 100, 3900));
        assertEquals(TorProcessPolicy.Expiry.NONE, policy.expiry(4099));
        assertEquals(TorProcessPolicy.Expiry.LEASE, policy.expiry(4100));
        assertFalse(policy.renew(1, 3, 4100, 4100));
    }

    @Test public void wrongSessionReplayedSequenceAndBackwardHeartbeatCannotRenew() {
        TorProcessPolicy policy = active(100);
        assertFalse(policy.renew(2, 2, 101, 101));
        assertFalse(policy.renew(1, 1, 101, 101));
        assertFalse(policy.renew(1, 0, 101, 101));
        assertFalse(policy.renew(1, 2, 99, 101));
        assertTrue(policy.renew(1, 2, 101, 101));
        assertEquals(TorProcessPolicy.Expiry.LEASE, policy.expiry(4101));
    }

    @Test public void heartbeatsCannotExtendAHungCommand() {
        TorProcessPolicy policy = active(0);
        assertTrue(policy.beginCommand(1, 10));
        assertTrue(policy.renew(1, 2, 1000, 1000));
        assertTrue(policy.renew(1, 3, 2000, 2000));
        assertTrue(policy.commandCurrent(1, 2009));
        assertEquals(TorProcessPolicy.Expiry.COMMAND, policy.expiry(2010));
        assertFalse(policy.finishCommand(1, 2010));
        assertFalse(policy.renew(1, 4, 2011, 2011));
    }

    @Test public void stopLatchesBeforeWorkerAndKeepsEarlierCommandDeadline() {
        TorProcessPolicy policy = active(0);
        assertTrue(policy.beginCommand(1, 0));
        assertTrue(policy.acceptStop(1, 2, 1900, 1900));
        assertTrue(policy.isStopping());
        assertFalse(policy.commandCurrent(1, 1900));
        assertFalse(policy.finishCommand(1, 1900));
        assertFalse(policy.claim(2, 1, 1901, 1901));
        assertFalse(policy.renew(1, 3, 1901, 1901));
        assertEquals(TorProcessPolicy.Expiry.COMMAND, policy.expiry(2000));
    }

    @Test public void repeatedStopNeverPushesTerminationDeadlineBack() {
        TorProcessPolicy policy = active(0);
        assertTrue(policy.stop(100));
        assertFalse(policy.stop(2099));
        assertEquals(TorProcessPolicy.Expiry.NONE, policy.expiry(2099));
        assertEquals(TorProcessPolicy.Expiry.STOP, policy.expiry(2100));
    }

    @Test public void ownerDeathBeforeClaimPreventsNetworkEnableForever() {
        TorProcessPolicy policy = new TorProcessPolicy(0);
        assertTrue(policy.stop(1));
        assertFalse(policy.claim(1, 1, 2, 2));
        assertFalse(policy.beginCommand(1, 2));
        assertEquals(TorProcessPolicy.Expiry.STOP, policy.expiry(2001));
    }

    @Test public void onlyOneOperationCanBePendingAndStaleReplyCannotClearDeadline() {
        TorProcessPolicy policy = active(0);
        assertTrue(policy.beginCommand(1, 0));
        assertFalse(policy.beginCommand(1, 1));
        assertTrue(policy.finishCommand(1, 100));
        assertTrue(policy.renew(1, 2, 200, 200));
        assertTrue(policy.beginCommand(2, 200));
        assertFalse(policy.finishCommand(1, 201));
        assertEquals(TorProcessPolicy.Expiry.COMMAND, policy.expiry(2200));
    }

    @Test public void healthySamplesClearOnlyTheirOwnDeadline() {
        TorProcessPolicy policy = active(0);
        assertTrue(policy.beginCommand(1, 0));
        assertTrue(policy.finishCommand(1, 100));
        assertTrue(policy.renew(1, 2, 500, 500));
        assertTrue(policy.beginCommand(2, 500));
        assertTrue(policy.finishCommand(2, 600));
        assertEquals(TorProcessPolicy.Expiry.NONE, policy.expiry(2500));
        assertEquals(TorProcessPolicy.Expiry.LEASE, policy.expiry(4500));
    }

    @Test public void lateReplyCannotResurrectExpiredLease() {
        TorProcessPolicy policy = active(0);
        assertTrue(policy.beginCommand(1, 3999));
        assertFalse(policy.finishCommand(1, 4000));
        assertEquals(TorProcessPolicy.Expiry.LEASE, policy.expiry(4000));
    }

    @Test public void crossThreadClockSamplingOrderDoesNotManufactureClockFailure() {
        TorProcessPolicy policy = active(100);
        assertEquals(TorProcessPolicy.Expiry.NONE, policy.expiry(200));
        assertTrue(policy.beginCommand(1, 199));
        assertTrue(policy.finishCommand(1, 201));
        assertEquals(TorProcessPolicy.Expiry.LEASE, policy.expiry(4100));
        assertFalse(policy.renew(1, 2, 4099, 4099));
    }

    @Test public void invalidClockFailsClosedAndArithmeticDoesNotWrap() {
        TorProcessPolicy policy = active(0);
        assertEquals(TorProcessPolicy.Expiry.CLOCK, policy.expiry(-1));
        assertFalse(policy.renew(1, 2, 1, 1));
        TorProcessPolicy large = new TorProcessPolicy(Long.MAX_VALUE - 10);
        assertTrue(large.claim(1, 1, Long.MAX_VALUE - 10, Long.MAX_VALUE - 10));
        assertTrue(large.stop(Long.MAX_VALUE - 9));
        assertEquals(TorProcessPolicy.Expiry.NONE, large.expiry(Long.MAX_VALUE - 1));
        assertEquals(TorProcessPolicy.Expiry.STOP, large.expiry(Long.MAX_VALUE));
    }

    private static TorProcessPolicy active(long now) {
        TorProcessPolicy policy = new TorProcessPolicy(now);
        assertTrue(policy.claim(1, 1, now, now));
        return policy;
    }
}
