package dev.gyeopdam.embeddedtor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Host-only Call fixture: tests the real OnionProbeSession without any transport or Netty. */
final class FixedOnionProbe implements OnionProbeSession.Call {
    static final List<FixedOnionProbe> calls = new ArrayList<>();
    final TorSocketEndpoint endpoint;
    final BooleanSupplier ready;
    Consumer<OnionProbeSession.Result> completion;
    int cancels;
    FixedOnionProbe(TorSocketEndpoint endpoint, BooleanSupplier ready) { this.endpoint = endpoint; this.ready = ready; calls.add(this); }
    public void start(Consumer<OnionProbeSession.Result> completed) { completion = completed; }
    public void cancel() { cancels++; }
    void lateSuccess() { completion.accept(new OnionProbeSession.Result(OnionProbeSession.State.RESPONSE, 200, 3)); }
}
