package dev.gyeopdam.embeddedtor;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Main-thread session state; the transport independently checks the same expiring Tor lease. */
final class OnionProbeSession {
    enum State { IDLE, RUNNING, RESPONSE, FAILED, TIMED_OUT, CANCELLED }
    enum Stage { SETUP, LOCAL_CONNECT, SOCKS_GREETING, ONION_CONNECT, HTTP_WRITE, HTTP_READ }
    enum Reason { NONE, IO_OR_PROTOCOL, INVALID_RESPONSE, RESPONSE_LIMIT, SOCKS_AUTH_REJECTED, SOCKS_CONNECT_REJECTED, DEADLINE, CANCELLED }
    static final class Result {
        final State state;
        final int httpStatus;
        final int bodyBytes;
        final Stage stage;
        final Reason reason;
        final long elapsedMillis;
        final int socksStatus;
        Result(State state, int httpStatus, int bodyBytes) {
            this(state, httpStatus, bodyBytes, Stage.SETUP, Reason.NONE, 0);
        }
        Result(State state, int httpStatus, int bodyBytes, Stage stage, Reason reason, long elapsedMillis) {
            this(state, httpStatus, bodyBytes, stage, reason, elapsedMillis, -1);
        }
        Result(State state, int httpStatus, int bodyBytes, Stage stage, Reason reason, long elapsedMillis, int socksStatus) {
            this.state = state; this.httpStatus = httpStatus; this.bodyBytes = bodyBytes;
            this.stage = stage; this.reason = reason; this.elapsedMillis = Math.max(0, elapsedMillis);
            this.socksStatus = socksStatus >= 0 && socksStatus <= 255 ? socksStatus : -1;
        }
        String diagnosticCode() { return stage.name() + ":" + reason.name()
                + (socksStatus < 0 ? "" : ":S" + socksStatus); }
        static Result of(State state) {
            Reason reason = state == State.FAILED ? Reason.IO_OR_PROTOCOL
                    : state == State.TIMED_OUT ? Reason.DEADLINE
                    : state == State.CANCELLED ? Reason.CANCELLED : Reason.NONE;
            return new Result(state, 0, 0, Stage.SETUP, reason, 0);
        }
    }
    interface Call {
        default void setProgressListener(Consumer<Result> listener) { }
        void start(Consumer<Result> completed);
        void cancel();
    }
    interface Factory { Call create(TorSocketEndpoint endpoint, BooleanSupplier stillReady); }

    private final TorReadinessGate gate;
    private final LongSupplier clock;
    private final Factory factory;
    private final Executor main;
    private final Runnable changed;
    private TorReadinessGate.ReadyLease lease;
    private Call call;
    private long requestEpoch;
    private Result result = Result.of(State.IDLE);

    OnionProbeSession(TorReadinessGate gate, LongSupplier clock, Factory factory,
                      Executor main, Runnable changed) {
        this.gate = gate; this.clock = clock; this.factory = factory;
        this.main = main; this.changed = changed;
    }
    Result result() { return result; }
    boolean canStart() { return call == null && gate.readyLease(clock.getAsLong()) != null; }
    void start() {
        if (call != null) return;
        TorReadinessGate.ReadyLease approved = gate.readyLease(clock.getAsLong());
        if (approved == null) return;
        lease = approved;
        long request = ++requestEpoch;
        result = Result.of(State.RUNNING);
        long setupStartedAt = clock.getAsLong();
        try {
            call = factory.create(approved.endpoint, () -> gate.isCurrent(approved, clock.getAsLong()));
            call.setProgressListener(value -> main.execute(() -> progress(request, approved, value)));
            call.start(value -> main.execute(() -> complete(request, approved, value)));
        } catch (RuntimeException | LinkageError unavailable) {
            if (call != null) call.cancel();
            call = null;
            result = new Result(State.FAILED, 0, 0, Stage.SETUP, Reason.IO_OR_PROTOCOL,
                    clock.getAsLong() - setupStartedAt);
        }
        changed.run();
    }
    private void progress(long request, TorReadinessGate.ReadyLease approved, Result value) {
        if (request != requestEpoch || call == null || value.state != State.RUNNING) return;
        if (!gate.isCurrent(approved, clock.getAsLong())) { cancel(); changed.run(); return; }
        result = value;
        changed.run();
    }
    private void complete(long request, TorReadinessGate.ReadyLease approved, Result value) {
        if (request != requestEpoch || call == null) return;
        call = null;
        result = gate.isCurrent(approved, clock.getAsLong()) ? value : Result.of(State.CANCELLED);
        changed.run();
    }
    /** Called before rendering every control sample/watchdog tick. Does not recursively render. */
    void refresh() {
        if (call != null && !gate.isCurrent(lease, clock.getAsLong())) cancel();
    }
    /** Invalidates callbacks before closing transport; used before Tor shutdown and on background. */
    void cancel() {
        ++requestEpoch;
        if (call != null) {
            Call active = call;
            call = null;
            result = Result.of(State.CANCELLED);
            active.cancel();
        }
    }
}
