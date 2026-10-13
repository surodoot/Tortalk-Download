package dev.gyeopdam.embeddedtor;

import org.junit.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import static org.junit.Assert.*;

public class DiagnosticLogTest {
    private long now = 1000;
    private final DiagnosticLog log = new DiagnosticLog(() -> now);

    @Test public void startsEmptyWithFixedVersionedHeader() {
        assertTrue(log.isEmpty());
        assertEquals(0, log.size());
        assertEquals("Tor diagnostic log version=0.4 format=1\n", log.snapshot());
    }

    @Test public void serializesOnlyFixedSchemaAndNumericMetadata() {
        log.recordAction(DiagnosticLog.Action.SCREEN_OPENED);
        now = 1025;
        log.recordTor(TorReadinessGate.Phase.STARTING, 10);
        now = 1050;
        log.recordProbe(OnionProbeSession.State.FAILED, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.IO_OR_PROTOCOL, -1, 0, 0, 49);
        assertEquals(DiagnosticLog.HEADER
                + "t=0ms ACTION event=SCREEN_OPENED\n"
                + "t=25ms TOR phase=STARTING progress=10\n"
                + "t=50ms PROBE state=FAILED stage=HTTP_READ reason=IO_OR_PROTOCOL socks=-1 http=0 bytes=0 probeMs=49\n",
                log.snapshot());
    }

    @Test public void repeatedControlPollsDoNotConsumeLogCapacity() {
        for (int i = 0; i < 2000; i++) {
            now++;
            log.recordTor(TorReadinessGate.Phase.READY, 100);
        }
        assertEquals(1, log.size());
        assertTrue(log.snapshot().contains("t=1ms TOR phase=READY progress=100\n"));
    }

    @Test public void torPhaseAndProgressChangesAreEachRecorded() {
        log.recordTor(TorReadinessGate.Phase.STARTING, 0);
        log.recordTor(TorReadinessGate.Phase.STARTING, 50);
        log.recordTor(TorReadinessGate.Phase.READY, 100);
        log.recordTor(TorReadinessGate.Phase.FAILED, 100);
        log.recordTor(TorReadinessGate.Phase.STARTING, 50);
        assertEquals(5, log.size());
    }

    @Test public void unchangedProbePollingIgnoresElapsedTimeOnly() {
        for (int i = 0; i < 2000; i++) {
            now++;
            probe(OnionProbeSession.State.RUNNING, OnionProbeSession.Stage.HTTP_READ,
                    OnionProbeSession.Reason.NONE, -1, 0, 0, i);
        }
        assertEquals(1, log.size());
        assertTrue(log.snapshot().contains("probeMs=0\n"));
    }

    @Test public void probeStateStageReasonAndMetadataChangesAreRecorded() {
        probe(OnionProbeSession.State.RUNNING, OnionProbeSession.Stage.SETUP,
                OnionProbeSession.Reason.NONE, -1, 0, 0, 0);
        probe(OnionProbeSession.State.RUNNING, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, -1, 0, 0, 1);
        probe(OnionProbeSession.State.RESPONSE, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, 0, 200, 10, 2);
        probe(OnionProbeSession.State.RESPONSE, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, 0, 200, 11, 3);
        probe(OnionProbeSession.State.RESPONSE, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, 0, 302, 11, 4);
        probe(OnionProbeSession.State.FAILED, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.IO_OR_PROTOCOL, 0, 0, 0, 5);
        probe(OnionProbeSession.State.FAILED, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.INVALID_RESPONSE, 0, 0, 0, 6);
        probe(OnionProbeSession.State.FAILED, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.INVALID_RESPONSE, 5, 0, 0, 7);
        assertEquals(8, log.size());
    }

    @Test public void separateTorAndProbeDedupSurvivesInterleavedActions() {
        log.recordTor(TorReadinessGate.Phase.READY, 100);
        probe(OnionProbeSession.State.RUNNING, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, 0, 0, 0, 1);
        log.recordAction(DiagnosticLog.Action.COPY_SUCCEEDED);
        log.recordTor(TorReadinessGate.Phase.READY, 100);
        probe(OnionProbeSession.State.RUNNING, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, 0, 0, 0, 1000);
        assertEquals(3, log.size());
    }

    @Test public void entryLimitEvictsOldestAndKeepsNewest() {
        for (int i = 0; i <= DiagnosticLog.MAX_ENTRIES; i++) {
            now = 1000 + i;
            log.recordAction(DiagnosticLog.Action.PROBE_REQUESTED);
        }
        assertEquals(128, log.size());
        String text = log.snapshot();
        assertFalse(text.contains("t=0ms "));
        assertTrue(text.contains("t=1ms "));
        assertTrue(text.endsWith("t=128ms ACTION event=PROBE_REQUESTED\n"));
        assertTrue(text.length() <= DiagnosticLog.MAX_CHARACTERS);
    }

    @Test public void characterLimitIncludesHeaderAndNeverSplitsAnEntry() {
        for (int i = 0; i < 10000; i++) {
            now = 1000L + i;
            probe(OnionProbeSession.State.TIMED_OUT, OnionProbeSession.Stage.HTTP_READ,
                    OnionProbeSession.Reason.IO_OR_PROTOCOL, 255, 599, i, Long.MAX_VALUE);
            assertTrue(log.size() <= DiagnosticLog.MAX_ENTRIES);
            assertTrue(log.snapshot().length() <= DiagnosticLog.MAX_CHARACTERS);
        }
        assertTrue("long entries must hit the character cap first", log.size() < 128);
        assertFalse(log.snapshot().contains("t=0ms "));
        assertTrue(log.snapshot().endsWith("bytes=9999 probeMs=9223372036854775807\n"));
        String[] lines = log.snapshot().split("\n");
        assertEquals(log.size() + 1, lines.length);
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].matches("t=[0-9]+ms PROBE state=TIMED_OUT stage=HTTP_READ reason=IO_OR_PROTOCOL socks=255 http=599 bytes=[0-9]+ probeMs=9223372036854775807"));
        }
    }

    @Test public void invalidNumericMetadataIsNormalizedBeforeDedupAndExport() {
        log.recordTor(TorReadinessGate.Phase.STARTING, Integer.MIN_VALUE);
        log.recordTor(TorReadinessGate.Phase.STARTING, -1);
        probe(OnionProbeSession.State.FAILED, OnionProbeSession.Stage.SETUP,
                OnionProbeSession.Reason.IO_OR_PROTOCOL, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE, Long.MIN_VALUE);
        probe(OnionProbeSession.State.FAILED, OnionProbeSession.Stage.SETUP,
                OnionProbeSession.Reason.IO_OR_PROTOCOL, -2, 99, -1, -1);
        assertEquals(2, log.size());
        assertTrue(log.snapshot().contains("progress=0\n"));
        assertTrue(log.snapshot().endsWith("socks=-1 http=0 bytes=0 probeMs=0\n"));
        log.recordTor(TorReadinessGate.Phase.READY, Integer.MAX_VALUE);
        probe(OnionProbeSession.State.RESPONSE, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, 256, 600, Integer.MAX_VALUE, Long.MAX_VALUE);
        assertTrue(log.snapshot().contains("progress=100\n"));
        assertTrue(log.snapshot().endsWith("socks=-1 http=0 bytes=98304 probeMs=9223372036854775807\n"));
    }

    @Test public void elapsedTimesNeverDecreaseOrUseWallClock() {
        now = 1200; log.recordAction(DiagnosticLog.Action.START_REQUESTED);
        now = 1100; log.recordAction(DiagnosticLog.Action.PROBE_REQUESTED);
        now = -1; log.recordAction(DiagnosticLog.Action.STOP_REQUESTED);
        now = 1300; log.recordAction(DiagnosticLog.Action.COPY_SUCCEEDED);
        assertEquals(DiagnosticLog.HEADER
                + "t=200ms ACTION event=START_REQUESTED\n"
                + "t=200ms ACTION event=PROBE_REQUESTED\n"
                + "t=200ms ACTION event=STOP_REQUESTED\n"
                + "t=300ms ACTION event=COPY_SUCCEEDED\n", log.snapshot());
    }

    @Test public void extremeClockValuesCannotOverflowElapsedTime() {
        final long[] value = {Long.MIN_VALUE};
        DiagnosticLog extreme = new DiagnosticLog(() -> value[0]);
        extreme.recordAction(DiagnosticLog.Action.SCREEN_OPENED);
        value[0] = Long.MAX_VALUE;
        extreme.recordAction(DiagnosticLog.Action.START_REQUESTED);
        assertTrue(extreme.snapshot().contains("t=0ms ACTION event=SCREEN_OPENED\n"));
        assertTrue(extreme.snapshot().endsWith("t=9223372036854775807ms ACTION event=START_REQUESTED\n"));
    }

    @Test public void clearRemovesAllEntriesAndResetTimeOrigin() {
        log.recordAction(DiagnosticLog.Action.START_REQUESTED);
        now = 5000; log.clear();
        assertTrue(log.isEmpty()); assertEquals(0, log.size());
        assertEquals(DiagnosticLog.HEADER, log.snapshot());
        now = 5020; log.recordAction(DiagnosticLog.Action.PROBE_REQUESTED);
        assertEquals(DiagnosticLog.HEADER + "t=20ms ACTION event=PROBE_REQUESTED\n", log.snapshot());
    }

    @Test public void clearStaysEmptyDuringUnchangedPollsButRecordsNextTransition() {
        log.recordTor(TorReadinessGate.Phase.READY, 100);
        probe(OnionProbeSession.State.RUNNING, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, 0, 0, 0, 1);
        log.clear();
        log.recordTor(TorReadinessGate.Phase.READY, 100);
        probe(OnionProbeSession.State.RUNNING, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, 0, 0, 0, 999);
        assertTrue(log.isEmpty());
        probe(OnionProbeSession.State.RESPONSE, OnionProbeSession.Stage.HTTP_READ,
                OnionProbeSession.Reason.NONE, 0, 200, 12, 1000);
        assertEquals(1, log.size());
        assertFalse(log.snapshot().contains("state=RUNNING"));
    }

    @Test public void freshActivityLogDoesNotInheritAnyPreviousState() {
        log.recordTor(TorReadinessGate.Phase.READY, 100);
        DiagnosticLog next = new DiagnosticLog(() -> now);
        assertTrue(next.isEmpty());
        next.recordTor(TorReadinessGate.Phase.READY, 100);
        assertEquals(1, next.size());
    }

    @Test public void exportIsLocaleIndependentAsciiWithoutEndpointOrExceptionFields() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"));
            for (OnionProbeSession.Stage stage : OnionProbeSession.Stage.values()) {
                for (OnionProbeSession.Reason reason : OnionProbeSession.Reason.values()) {
                    probe(OnionProbeSession.State.FAILED, stage, reason, 5, 503, 10, 123);
                }
            }
            String text = log.snapshot();
            assertTrue(text.matches("[A-Za-z0-9_= .\\-\\n]+"));
            assertFalse(text.contains(".onion"));
            assertFalse(text.contains("127.0.0.1"));
            assertFalse(text.contains("http://"));
            assertFalse(text.contains("Exception"));
            assertFalse(text.contains("token="));
            assertFalse(text.contains("address="));
            assertFalse(text.contains("message="));
            assertFalse(text.contains("device="));
        } finally { Locale.setDefault(previous); }
    }

    @Test public void externallyCallableRecordMethodsCannotAcceptRawTextOrExceptions() {
        int checked = 0;
        for (Method method : DiagnosticLog.class.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers())) continue;
            for (Class<?> input : method.getParameterTypes()) {
                assertTrue("non-fixed input on " + method.getName(), input.isEnum()
                        || input == int.class || input == long.class || input == boolean.class);
                checked++;
            }
        }
        assertEquals(10, checked);
    }

    private void probe(OnionProbeSession.State state, OnionProbeSession.Stage stage,
                       OnionProbeSession.Reason reason, int socksStatus, int httpStatus,
                       int bytes, long elapsedMillis) {
        log.recordProbe(state, stage, reason, socksStatus, httpStatus, bytes, elapsedMillis);
    }
}
