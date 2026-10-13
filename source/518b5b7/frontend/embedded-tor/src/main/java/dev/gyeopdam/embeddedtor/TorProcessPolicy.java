package dev.gyeopdam.embeddedtor;

/** Pure, short critical sections only: no Binder, control I/O or callbacks under its monitor. */
final class TorProcessPolicy {
    static final long COMMAND_MS = 2_000, LEASE_MS = 4_000, STARTUP_MS = 5_000, STOP_MS = 2_000;
    enum Expiry { NONE, STARTUP, LEASE, COMMAND, STOP, CLOCK }
    private final long startupDeadline;
    private long lastNow, epoch, lastSequence, lastSentAt, leaseDeadline;
    private long commandSequence, commandDeadline, stopDeadline;
    private boolean claimed, stopping, clockFailed;

    TorProcessPolicy(long now) {
        if (now < 0) throw new IllegalArgumentException("CLOCK");
        lastNow = now;
        startupDeadline = deadline(now, STARTUP_MS);
    }

    synchronized boolean claim(long attempt, long sequence, long sentAt, long now) {
        now = clock(now);
        if (clockFailed || claimed || stopping || now >= startupDeadline
                || attempt <= 0 || sequence <= 0 || !fresh(sentAt, now)) return false;
        claimed = true;
        epoch = attempt;
        lastSequence = sequence;
        lastSentAt = sentAt;
        leaseDeadline = deadline(sentAt, LEASE_MS);
        return true;
    }

    synchronized boolean renew(long attempt, long sequence, long sentAt, long now) {
        now = clock(now);
        if (!active(now) || attempt != epoch || sequence <= lastSequence
                || sentAt < lastSentAt || !fresh(sentAt, now)) return false;
        lastSequence = sequence;
        lastSentAt = sentAt;
        leaseDeadline = deadline(sentAt, LEASE_MS);
        return true;
    }

    synchronized boolean acceptStop(long attempt, long sequence, long sentAt, long now) {
        now = clock(now);
        if (clockFailed || !claimed || stopping || attempt != epoch
                || sequence <= lastSequence || sentAt < lastSentAt || !fresh(sentAt, now)) return false;
        lastSequence = sequence;
        lastSentAt = sentAt;
        stop(now);
        return true;
    }

    synchronized boolean beginCommand(long sequence, long now) {
        now = clock(now);
        if (!active(now) || sequence != lastSequence || commandSequence != 0) return false;
        commandSequence = sequence;
        commandDeadline = deadline(now, COMMAND_MS);
        return true;
    }

    synchronized boolean commandCurrent(long sequence, long now) {
        now = clock(now);
        return active(now) && commandSequence == sequence && sequence > 0 && now < commandDeadline;
    }

    synchronized boolean finishCommand(long sequence, long now) {
        if (!commandCurrent(sequence, now)) return false;
        commandSequence = 0;
        commandDeadline = 0;
        return true;
    }

    synchronized boolean stop(long now) {
        now = clock(now);
        if (stopping) return false;
        stopping = true;
        stopDeadline = deadline(now, STOP_MS);
        return true;
    }

    synchronized Expiry expiry(long now) {
        now = clock(now);
        if (clockFailed) return Expiry.CLOCK;
        if (commandSequence != 0 && now >= commandDeadline) return Expiry.COMMAND;
        if (stopping) return now >= stopDeadline ? Expiry.STOP : Expiry.NONE;
        if (!claimed) return now >= startupDeadline ? Expiry.STARTUP : Expiry.NONE;
        return now >= leaseDeadline ? Expiry.LEASE : Expiry.NONE;
    }

    synchronized boolean isStopping() { return stopping; }
    synchronized long epoch() { return epoch; }
    synchronized long sequence() { return lastSequence; }

    private boolean active(long now) {
        return !clockFailed && claimed && !stopping && now < leaseDeadline
                && (commandSequence == 0 || now < commandDeadline);
    }

    private long clock(long now) {
        // Callers may sample the same monotonic clock on different threads just before
        // acquiring this monitor. Ordering those calls must not manufacture rollback.
        if (now < 0) clockFailed = true;
        if (now >= lastNow) lastNow = now;
        return lastNow;
    }

    private static boolean fresh(long sentAt, long now) {
        return sentAt >= 0 && sentAt <= now && now - sentAt < LEASE_MS;
    }

    private static long deadline(long now, long duration) {
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }
}
