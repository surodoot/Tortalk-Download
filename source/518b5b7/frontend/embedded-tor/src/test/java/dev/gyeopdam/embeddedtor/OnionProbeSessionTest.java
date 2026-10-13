package dev.gyeopdam.embeddedtor;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class OnionProbeSessionTest {
    private static final String PATH = "/data/user/0/test/files/tor-socks/s";
    private static final int UID = 10001;
    private final TorReadinessGate gate = new TorReadinessGate(PATH, UID);
    private final ArrayDeque<Runnable> deliveries = new ArrayDeque<>();
    private final List<FakeCall> calls = new ArrayList<>();
    private long now;
    private final long epoch = gate.begin(0);
    private final OnionProbeSession session = new OnionProbeSession(gate, () -> now,
            (endpoint, current) -> { FakeCall call = new FakeCall(endpoint, current); calls.add(call); return call; },
            deliveries::add, () -> {});

    @Test public void noRequestBeforeReadyOrAfterExpiry() {
        assertFalse(session.canStart()); session.start(); assertTrue(calls.isEmpty());
        ready(1, 43123); now = 2002;
        assertFalse(session.canStart()); session.start(); assertTrue(calls.isEmpty());
    }
    @Test public void duplicateClicksCannotOpenAnotherConnection() {
        ready(1, 43123); session.start(); session.start();
        assertEquals(1, calls.size()); assertEquals(43123, calls.get(0).endpoint.pid);
        assertFalse(session.canStart()); assertEquals(OnionProbeSession.State.RUNNING, session.result().state);
    }
    @Test public void freshSamplesKeepOneLeaseAndCompletedStatusIsDistinct() {
        ready(1, 43123); session.start(); ready(500, 43123); session.refresh();
        assertFalse(calls.get(0).cancelled);
        calls.get(0).reply(response(302)); deliveries.remove().run();
        assertEquals(OnionProbeSession.State.RESPONSE, session.result().state);
        assertEquals(302, session.result().httpStatus); assertTrue(session.canStart());
    }
    @Test public void staleReadinessClosesRequestAndRejectsQueuedSuccess() {
        ready(1, 43123); session.start(); calls.get(0).reply(response(200)); now = 2002;
        session.refresh(); assertTrue(calls.get(0).cancelled);
        deliveries.remove().run(); assertEquals(OnionProbeSession.State.CANCELLED, session.result().state);
    }
    @Test public void completionChecksLeaseEvenBeforeMainWatchdogRuns() {
        ready(1, 43123); session.start(); calls.get(0).reply(response(200)); now = 2002;
        deliveries.remove().run(); assertEquals(OnionProbeSession.State.CANCELLED, session.result().state);
    }
    @Test public void backgroundOrStopCancellationRejectsLateCompletion() {
        ready(1, 43123); session.start(); gate.stop(); session.cancel();
        assertTrue(calls.get(0).cancelled); assertFalse(calls.get(0).current.getAsBoolean());
        calls.get(0).reply(response(200)); deliveries.remove().run();
        assertEquals(OnionProbeSession.State.CANCELLED, session.result().state); assertFalse(session.canStart());
    }
    @Test public void briefCircuitLossAndRecoveryCannotRevalidateOldRequest() {
        ready(1, 43123); session.start();
        gate.sample(epoch, 2, "PROGRESS=100", "0", "\"unix:" + PATH + "\"", 43123);
        ready(3, 43123); session.refresh();
        assertTrue(calls.get(0).cancelled); assertFalse(calls.get(0).current.getAsBoolean());
        assertTrue(session.canStart());
    }
    @Test public void changedExpectedPeerPidCancelsOldLease() {
        ready(1, 43123); session.start(); ready(2, 43124); session.refresh();
        assertTrue(calls.get(0).cancelled); session.start(); assertEquals(1, calls.size());
        assertFalse(session.canStart()); assertEquals(TorReadinessGate.Phase.FAILED, gate.phase(now));
    }
    @Test public void previousRequestCannotOverwriteNewRequest() {
        ready(1, 43123); session.start(); session.cancel(); session.start();
        calls.get(0).reply(response(200)); deliveries.remove().run();
        assertEquals(OnionProbeSession.State.RUNNING, session.result().state);
        calls.get(1).reply(OnionProbeSession.Result.of(OnionProbeSession.State.TIMED_OUT)); deliveries.remove().run();
        assertEquals(OnionProbeSession.State.TIMED_OUT, session.result().state);
    }
    @Test public void newTorAttemptRejectsOldLeaseAtSamePathAndPid() {
        ready(1, 43123); session.start();
        long next = gate.begin(2); now = 3;
        gate.sample(next, now, "PROGRESS=100", "1", "\"unix:" + PATH + "\"", 43123); session.refresh();
        assertTrue(calls.get(0).cancelled); assertFalse(calls.get(0).current.getAsBoolean());
    }
    @Test public void throwingFactoryReportsSanitizedSetupFailure() {
        ready(1, 43123);
        OnionProbeSession broken = new OnionProbeSession(gate, () -> now,
                (endpoint, current) -> { now = 25; throw new LinkageError("private raw diagnostic must not escape"); },
                deliveries::add, () -> {});
        broken.start();
        assertEquals(OnionProbeSession.State.FAILED, broken.result().state);
        assertEquals(24, broken.result().elapsedMillis);
        assertEquals("SETUP:IO_OR_PROTOCOL", broken.result().diagnosticCode());
        assertTrue(broken.canStart());
    }
    @Test public void throwingStartCancelsCallAndReportsSetupFailure() {
        ready(1, 43123);
        final boolean[] cancelled = {false};
        OnionProbeSession broken = new OnionProbeSession(gate, () -> now,
                (endpoint, current) -> new OnionProbeSession.Call() {
                    public void start(Consumer<OnionProbeSession.Result> callback) {
                        throw new IllegalStateException("private raw diagnostic must not escape");
                    }
                    public void cancel() { cancelled[0] = true; }
                }, deliveries::add, () -> {});
        broken.start();
        assertTrue(cancelled[0]);
        assertEquals("SETUP:IO_OR_PROTOCOL", broken.result().diagnosticCode());
        assertTrue(broken.canStart());
    }
    @Test public void progressKeepsRequestRunningAndPreservesTypedStage() {
        ready(1, 43123); session.start();
        calls.get(0).progress.accept(new OnionProbeSession.Result(OnionProbeSession.State.RUNNING, 0, 0,
                OnionProbeSession.Stage.ONION_CONNECT, OnionProbeSession.Reason.NONE, 15));
        deliveries.remove().run();
        assertEquals(OnionProbeSession.Stage.ONION_CONNECT, session.result().stage);
        assertFalse(session.canStart());
        assertEquals(15, session.result().elapsedMillis);
    }
    @Test public void staleProgressCannotOverwriteCompleteOrNewRequest() {
        ready(1, 43123); session.start();
        calls.get(0).reply(response(200)); deliveries.remove().run();
        calls.get(0).progress.accept(new OnionProbeSession.Result(OnionProbeSession.State.RUNNING, 0, 0,
                OnionProbeSession.Stage.SOCKS_GREETING, OnionProbeSession.Reason.NONE, 25));
        deliveries.remove().run();
        assertEquals(OnionProbeSession.State.RESPONSE, session.result().state);
        session.start();
        calls.get(0).progress.accept(new OnionProbeSession.Result(OnionProbeSession.State.RUNNING, 0, 0,
                OnionProbeSession.Stage.ONION_CONNECT, OnionProbeSession.Reason.NONE, 30));
        deliveries.remove().run();
        assertEquals(OnionProbeSession.Stage.SETUP, session.result().stage);
    }
    @Test public void progressWithExpiredLeaseCancelsWithoutResurrection() {
        ready(1, 43123); session.start(); now=2002;
        calls.get(0).progress.accept(new OnionProbeSession.Result(OnionProbeSession.State.RUNNING, 0, 0,
                OnionProbeSession.Stage.ONION_CONNECT, OnionProbeSession.Reason.NONE, 25));
        deliveries.remove().run();
        assertEquals(OnionProbeSession.State.CANCELLED, session.result().state);
        assertTrue(calls.get(0).cancelled);
    }
    private void ready(long time, int pid) {
        now = time; gate.sample(epoch, time, "PROGRESS=100", "1", "\"unix:" + PATH + "\"", pid);
    }
    private static OnionProbeSession.Result response(int code) {
        return new OnionProbeSession.Result(OnionProbeSession.State.RESPONSE, code, 2);
    }
    private static final class FakeCall implements OnionProbeSession.Call {
        final TorSocketEndpoint endpoint;
        final BooleanSupplier current;
        Consumer<OnionProbeSession.Result> callback;
        Consumer<OnionProbeSession.Result> progress;
        boolean cancelled;
        FakeCall(TorSocketEndpoint endpoint, BooleanSupplier current) { this.endpoint = endpoint; this.current = current; }
        @Override public void setProgressListener(Consumer<OnionProbeSession.Result> listener) { progress = listener; }
        @Override public void start(Consumer<OnionProbeSession.Result> completed) { callback = completed; }
        @Override public void cancel() { cancelled = true; }
        void reply(OnionProbeSession.Result result) { callback.accept(result); }
    }
}
