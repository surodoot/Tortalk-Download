package dev.gyeopdam.embeddedtor;

import org.junit.Test;
import static org.junit.Assert.*;

public class TorReadinessGateTest {
    private static final String DONE = "NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"";
    private static final String PATH = "/data/user/0/test/files/tor-socks/s";
    private static final int UID = 10001, PID = 22002;
    private static final TorSocketEndpoint ENDPOINT = new TorSocketEndpoint(PATH, UID, PID);
    private static final String LOCAL = ENDPOINT.controlListener();
    @Test public void stoppedNeverExposesProxy() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID);
        assertEquals(TorReadinessGate.Phase.STOPPED, gate.phase(0));
        assertEquals(null, gate.readyEndpoint(0));
    }
    @Test public void requiresBootstrapAndCircuitAndListenerTogether() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
        gate.sample(id, 1, "NOTICE BOOTSTRAP PROGRESS=80 TAG=conn", "1", LOCAL, PID);
        assertEquals(80, gate.progress()); assertEquals(null, gate.readyEndpoint(1));
        gate.sample(id, 2, DONE, "0", LOCAL, PID); assertEquals(null, gate.readyEndpoint(2));
        gate.sample(id, 3, DONE, "1", LOCAL, PID); assertEquals(ENDPOINT, gate.readyEndpoint(3));
    }
    @Test public void malformedTcpAbstractOrWrongFilesystemListenerFailsClosed() {
        for (String listener : new String[]{null, "", "127.0.0.1:43210", "\"0.0.0.0:43210\"",
                "\"localhost:43210\"", "\"127.0.0.2:43210\"", "\"127.0.0.1:0\"",
                "\"127.0.0.1:65536\"", "\"unix:@tor-socks\"", "\"unix:/tmp/foreign\"",
                "unix:" + PATH, "\"unix:" + PATH + "/../s\"", LOCAL + " " + LOCAL, LOCAL + "\n"}) {
            TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
            gate.sample(id, 1, DONE, "1", listener, PID); assertEquals(null, gate.readyEndpoint(1));
        }
    }
    @Test public void malformedOrDuplicateBootstrapNeverEnablesProxy() {
        for (String bootstrap : new String[]{null, "", "PROGRESS=1000", "PROGRESS=-1", "XPROGRESS=100",
                "PROGRESS=100 PROGRESS=50", "PROGRESS=100x"}) {
            TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
            gate.sample(id, 1, bootstrap, "1", LOCAL, PID); assertEquals(null, gate.readyEndpoint(1));
        }
    }
    @Test public void staleReadyStateIsTerminalUntilNewExplicitAttempt() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
        gate.sample(id, 10, DONE, "1", LOCAL, PID); assertEquals(ENDPOINT, gate.readyEndpoint(2010));
        assertEquals(null, gate.readyEndpoint(2011));
        gate.sample(id, 2012, DONE, "1", LOCAL, PID); assertEquals(TorReadinessGate.Phase.FAILED, gate.phase(2012));
    }
    @Test public void stopRejectsLateControlRepliesAndRevokeBeforeShutdown() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
        gate.sample(id, 1, DONE, "1", LOCAL, PID); gate.stop();
        gate.sample(id, 2, DONE, "1", LOCAL, PID); assertEquals(null, gate.readyEndpoint(2));
        assertEquals(TorReadinessGate.Phase.STOPPED, gate.phase(2));
    }
    @Test public void circuitLossRevokesReadyImmediatelyOnNextSample() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
        gate.sample(id, 1, DONE, "1", LOCAL, PID); gate.sample(id, 2, DONE, "0", LOCAL, PID);
        assertEquals(null, gate.readyEndpoint(2));
    }
    @Test public void missingControlDataRevokesReady() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
        gate.sample(id, 1, DONE, "1", LOCAL, PID); gate.sample(id, 2, null, null, null, PID);
        assertEquals(null, gate.readyEndpoint(2));
    }
    @Test public void timeoutAndLateSuccessfulReplyCannotResurrectStartup() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
        gate.sample(id, 120001, DONE, "1", LOCAL, PID);
        assertEquals(TorReadinessGate.Phase.FAILED, gate.phase(120001));
    }
    @Test public void differentAttemptRejectsOldFailureAndResponse() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long old = gate.begin(0); long current = gate.begin(1);
        gate.fail(old); gate.sample(old, 2, DONE, "1", LOCAL, PID);
        assertEquals(TorReadinessGate.Phase.STARTING, gate.phase(2));
        gate.sample(current, 3, DONE, "1", LOCAL, PID); assertEquals(ENDPOINT, gate.readyEndpoint(3));
    }
    @Test public void monotonicClockRollbackFailsClosed() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(100);
        gate.sample(id, 101, DONE, "1", LOCAL, PID); assertEquals(null, gate.readyEndpoint(100));
    }
    @Test public void explicitFailureAlwaysRevokesAndIgnoresFurtherSamples() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
        gate.sample(id, 1, DONE, "1", LOCAL, PID); gate.fail(id);
        gate.sample(id, 2, DONE, "1", LOCAL, PID); assertEquals(null, gate.readyEndpoint(2));
        assertEquals(TorReadinessGate.Phase.FAILED, gate.phase(2));
    }
    @Test public void invalidProcessIdNeverCreatesEndpoint() {
        for (int pid : new int[]{0, -1}) {
            TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
            gate.sample(id, 1, DONE, "1", LOCAL, pid);
            assertNull(gate.readyEndpoint(1)); assertNull(gate.readyLease(1));
        }
    }
    @Test public void leaseIncludesExpectedUidAndProcessId() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
        gate.sample(id, 1, DONE, "1", LOCAL, PID);
        TorReadinessGate.ReadyLease lease = gate.readyLease(1);
        assertEquals(PATH, lease.endpoint.path); assertEquals(UID, lease.endpoint.uid);
        assertEquals(PID, lease.endpoint.pid); assertTrue(gate.isCurrent(lease, 1));
    }
    @Test public void changedPidRevokesOldLeaseAtSameFilesystemPath() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long id = gate.begin(0);
        gate.sample(id, 1, DONE, "1", LOCAL, PID); TorReadinessGate.ReadyLease old = gate.readyLease(1);
        gate.sample(id, 2, DONE, "1", LOCAL, PID + 1);
        assertFalse(gate.isCurrent(old, 2)); assertEquals(TorReadinessGate.Phase.FAILED, gate.phase(2));
        gate.sample(id, 3, DONE, "1", LOCAL, PID); assertNull(gate.readyEndpoint(3));
    }
    @Test public void sameEndpointOnNewEpochCannotReviveOldLease() {
        TorReadinessGate gate = new TorReadinessGate(PATH, UID); long oldEpoch = gate.begin(0);
        gate.sample(oldEpoch, 1, DONE, "1", LOCAL, PID); TorReadinessGate.ReadyLease old = gate.readyLease(1);
        long current = gate.begin(2); gate.sample(current, 3, DONE, "1", LOCAL, PID);
        gate.sample(oldEpoch, 4, DONE, "1", LOCAL, PID + 1); gate.fail(oldEpoch);
        assertFalse(gate.isCurrent(old, 4)); assertEquals(ENDPOINT, gate.readyEndpoint(4));
    }
}

