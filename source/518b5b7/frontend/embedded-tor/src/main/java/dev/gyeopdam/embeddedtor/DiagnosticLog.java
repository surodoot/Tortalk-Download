package dev.gyeopdam.embeddedtor;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Activity-owned, memory-only diagnostics. No arbitrary text, Throwable, endpoint or payload input.
 * The schema deliberately accepts only fixed enums and bounded numeric protocol metadata.
 */
final class DiagnosticLog {
    static final int MAX_ENTRIES = 128;
    static final int MAX_CHARACTERS = 8192;
    static final String HEADER = "Tor diagnostic log version=0.4 format=1\n";
    enum Action { SCREEN_OPENED, START_REQUESTED, STOP_REQUESTED, PROBE_REQUESTED,
        SCREEN_STOPPED, COPY_SUCCEEDED, COPY_FAILED }

    private final LongSupplier clock;
    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private int entryCharacters;
    private long originMillis;
    private long lastElapsedMillis;
    private String lastTor;
    private String lastProbe;

    DiagnosticLog(LongSupplier monotonicClock) {
        clock = Objects.requireNonNull(monotonicClock);
        originMillis = Math.max(0, clock.getAsLong());
    }

    void recordAction(Action action) {
        append("ACTION event=" + Objects.requireNonNull(action).name());
    }

    void recordTor(TorReadinessGate.Phase phase, int progress) {
        String value = "TOR phase=" + Objects.requireNonNull(phase).name()
                + " progress=" + Math.max(0, Math.min(100, progress));
        if (value.equals(lastTor)) return;
        lastTor = value;
        append(value);
    }

    void recordProbe(OnionProbeSession.State state, OnionProbeSession.Stage stage,
                     OnionProbeSession.Reason reason, int socksStatus, int httpStatus,
                     int bodyBytes, long elapsedMillis) {
        String value = "PROBE state=" + Objects.requireNonNull(state).name()
                + " stage=" + Objects.requireNonNull(stage).name()
                + " reason=" + Objects.requireNonNull(reason).name()
                + " socks=" + (socksStatus >= 0 && socksStatus <= 255 ? socksStatus : -1)
                + " http=" + (httpStatus >= 100 && httpStatus <= 599 ? httpStatus : 0)
                + " bytes=" + Math.max(0, Math.min(96 * 1024, bodyBytes));
        // Polling and elapsed-time changes alone are not new diagnostic transitions.
        if (value.equals(lastProbe)) return;
        lastProbe = value;
        append(value + " probeMs=" + Math.max(0, elapsedMillis));
    }

    private void append(String value) {
        long now = Math.max(0, clock.getAsLong());
        long elapsed = now >= originMillis ? now - originMillis : 0;
        lastElapsedMillis = Math.max(lastElapsedMillis, elapsed);
        String entry = "t=" + lastElapsedMillis + "ms " + value + "\n";
        // Fixed enum names and bounded numbers keep one entry far below the total character cap.
        if (entry.length() + HEADER.length() > MAX_CHARACTERS) return;
        while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES
                || HEADER.length() + entryCharacters + entry.length() > MAX_CHARACTERS)) {
            entryCharacters -= entries.removeFirst().length();
        }
        entries.addLast(entry);
        entryCharacters += entry.length();
    }

    String snapshot() {
        StringBuilder output = new StringBuilder(HEADER.length() + entryCharacters);
        output.append(HEADER);
        for (String entry : entries) output.append(entry);
        return output.toString();
    }

    boolean isEmpty() { return entries.isEmpty(); }
    int size() { return entries.size(); }

    /**
     * Discard entries and reset elapsed time. Keep the last safe state fingerprints so the next
     * unchanged UI/control poll cannot immediately repopulate a log the user just cleared.
     * Does not access the clipboard, storage, Android logs or the network.
     */
    void clear() {
        entries.clear();
        entryCharacters = 0;
        originMillis = Math.max(0, clock.getAsLong());
        lastElapsedMillis = 0;
    }
}
