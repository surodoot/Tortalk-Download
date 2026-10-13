package dev.gyeopdam.embeddedtor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure state gate. A bootstrap result is never a delivery, identity or encryption receipt. */
public final class TorReadinessGate {
    public enum Phase { STOPPED, STARTING, READY, FAILED }
    private static final Pattern PROGRESS = Pattern.compile("(?<!\\S)PROGRESS=(100|[1-9]?[0-9])(?!\\S)");
    private final String expectedSocksPath;
    private final int appUid;
    private static final long START_TIMEOUT_MS = 120_000;
    private static final long FRESHNESS_MS = 2_000;
    private long epoch;
    private long readyGeneration;
    private long startedAt;
    private long sampledAt;
    private int progress;
    private int processPid;
    private TorSocketEndpoint endpoint;
    private Phase phase = Phase.STOPPED;

    TorReadinessGate(String expectedSocksPath, int appUid) {
        TorSocketEndpoint.validatePath(expectedSocksPath);
        if (appUid < 0) throw new IllegalArgumentException("INVALID_APP_UID");
        this.expectedSocksPath = expectedSocksPath; this.appUid = appUid;
    }

    public synchronized long begin(long now) {
        epoch++;
        startedAt = now;
        sampledAt = now;
        progress = 0;
        endpoint = null;
        processPid = 0;
        phase = Phase.STARTING;
        return epoch;
    }
    public synchronized void stop() { epoch++; phase = Phase.STOPPED; endpoint = null; processPid = 0; progress = 0; }
    public synchronized void fail(long expectedEpoch) {
        if (epoch == expectedEpoch && (phase == Phase.STARTING || phase == Phase.READY)) {
            phase = Phase.FAILED; endpoint = null;
        }
    }
    public synchronized void sample(long expectedEpoch, long now, String bootstrap,
                                    String circuitEstablished, String listeners, int sampledPid) {
        if (expectedEpoch != epoch || (phase != Phase.STARTING && phase != Phase.READY)) return;
        // A slow control reply cannot resurrect an already expired attempt.
        expire(now);
        if (phase == Phase.FAILED) return;
        if (sampledPid <= 0 || processPid != 0 && processPid != sampledPid) { fail(expectedEpoch); return; }
        processPid = sampledPid;
        TorSocketEndpoint previousEndpoint = endpoint;
        Phase previousPhase = phase;
        sampledAt = now;
        endpoint = null;
        progress = 0;
        if (bootstrap != null) {
            Matcher match = PROGRESS.matcher(bootstrap);
            if (match.find()) {
                progress = Integer.parseInt(match.group(1));
                if (match.find()) progress = 0; // ambiguous duplicate progress is not trusted
            }
        }
        phase = Phase.STARTING;
        if (progress == 100 && "1".equals(circuitEstablished)) {
            TorSocketEndpoint candidate = new TorSocketEndpoint(expectedSocksPath, appUid, processPid);
            if (candidate.controlListener().equals(listeners)) { endpoint = candidate; phase = Phase.READY; }
        }
        if (phase != previousPhase || !java.util.Objects.equals(endpoint, previousEndpoint)) readyGeneration++;
    }
    private void expire(long now) {
        if (now < sampledAt || (phase == Phase.READY && now - sampledAt > FRESHNESS_MS)
                || (phase == Phase.STARTING && now - startedAt > START_TIMEOUT_MS)) {
            phase = Phase.FAILED; endpoint = null;
        }
    }
    public synchronized Phase phase(long now) { if (phase == Phase.STARTING || phase == Phase.READY) expire(now); return phase; }
    public synchronized int progress() { return progress; }
    /** Hardwired filesystem endpoint only; no control-reply-selected path or TCP fallback. */
    synchronized TorSocketEndpoint readyEndpoint(long now) { return phase(now) == Phase.READY ? endpoint : null; }

    /** A request lease survives fresh samples, but never loss/recovery or listener replacement. */
    public static final class ReadyLease {
        private final long epoch;
        private final long generation;
        final TorSocketEndpoint endpoint;
        private ReadyLease(long epoch, long generation, TorSocketEndpoint endpoint) {
            this.epoch = epoch; this.generation = generation; this.endpoint = endpoint;
        }
    }
    public synchronized ReadyLease readyLease(long now) {
        return phase(now) == Phase.READY ? new ReadyLease(epoch, readyGeneration, endpoint) : null;
    }
    public synchronized boolean isCurrent(ReadyLease lease, long now) {
        return lease != null && phase(now) == Phase.READY && lease.epoch == epoch
                && lease.generation == readyGeneration && lease.endpoint.equals(endpoint);
    }
}
