package dev.gyeopdam.embeddedtor;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.resolver.NoopAddressResolverGroup;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One fixed public GET. No arbitrary endpoint, credential, DNS, redirect, retry, or direct fallback. */
final class FixedOnionProbe implements OnionProbeSession.Call {
    // Official Tor Project directory, verified at https://onion.torproject.org/ on 2026-10-08.
    static final String HOST = "xao2lxsmia2edq2n5zxg6uahx6xox2t7bfjw6b5vdzsxi7ezmqob6qid.onion";
    static final int TOTAL_TIMEOUT_MILLIS = 30_000;
    static final int LOCAL_CONNECT_TIMEOUT_MILLIS = 10_000;
    static final int MAX_BODY_BYTES = 96 * 1024;
    private final TorSocketEndpoint endpoint;
    private final TorSocketIo.Factory sockets;
    private final BooleanSupplier stillReady;
    private final int deadlineMillis;
    private final CompletableFuture<OnionProbeSession.Result> completed = new CompletableFuture<>();
    private boolean started;
    private volatile long startedAtNanos;
    private volatile long deadlineNanos;
    private volatile OnionProbeSession.Stage stage = OnionProbeSession.Stage.SETUP;
    private volatile int socksStatus = -1;
    private volatile OnionProbeSession.Reason socksFailure = OnionProbeSession.Reason.NONE;
    private volatile Consumer<OnionProbeSession.Result> progressListener = value -> { };
    private volatile boolean cancelled;
    private volatile TorSocketChannel channel;
    private volatile Thread worker;

    FixedOnionProbe(TorSocketEndpoint endpoint, BooleanSupplier stillReady) { this(endpoint, stillReady, TOTAL_TIMEOUT_MILLIS); }
    FixedOnionProbe(TorSocketEndpoint endpoint, BooleanSupplier stillReady, int deadlineMillis) {
        this(endpoint, stillReady, deadlineMillis, AndroidTorSocket::new);
    }
    FixedOnionProbe(TorSocketEndpoint endpoint, BooleanSupplier stillReady, int deadlineMillis, TorSocketIo.Factory sockets) {
        if (endpoint == null || deadlineMillis < 1 || deadlineMillis > TOTAL_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("Invalid probe limits");
        }
        this.endpoint = endpoint; this.sockets = sockets;
        this.stillReady = stillReady; this.deadlineMillis = deadlineMillis;
    }
    @Override public synchronized void setProgressListener(Consumer<OnionProbeSession.Result> listener) {
        if (started) throw new IllegalStateException("Already started");
        progressListener = listener;
    }
    private void transition(OnionProbeSession.Stage next) {
        stage = next;
        if (!cancelled && !completed.isDone()) {
            progressListener.accept(diagnostic(OnionProbeSession.State.RUNNING, OnionProbeSession.Reason.NONE));
        }
    }
    @Override public synchronized void start(Consumer<OnionProbeSession.Result> callback) {
        if (started) throw new IllegalStateException("One shot only");
        started = true;
        startedAtNanos = System.nanoTime();
        long deadline = startedAtNanos + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        deadlineNanos = deadline;
        worker = new Thread(() -> callback.accept(exchange(deadline)), "onion-probe");
        worker.setDaemon(true);
        worker.start();
    }
    @Override public void cancel() {
        cancelled = true;
        finish(diagnostic(OnionProbeSession.State.CANCELLED, OnionProbeSession.Reason.CANCELLED));
        Thread active = worker;
        if (active != null) active.interrupt();
    }
    private OnionProbeSession.Result diagnostic(OnionProbeSession.State state, OnionProbeSession.Reason reason) {
        long elapsed = startedAtNanos == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        return new OnionProbeSession.Result(state, 0, 0, stage, reason, elapsed, socksStatus);
    }
    private OnionProbeSession.Result failed() {
        return diagnostic(OnionProbeSession.State.FAILED, socksFailure == OnionProbeSession.Reason.NONE
                ? OnionProbeSession.Reason.IO_OR_PROTOCOL : socksFailure);
    }
    private OnionProbeSession.Result failed(Throwable cause) {
        // Inspect fixed Java types only; never retain exception messages, addresses, or stack traces.
        if (cause instanceof ConnectTimeoutException) {
            return diagnostic(OnionProbeSession.State.TIMED_OUT, OnionProbeSession.Reason.DEADLINE);
        }
        if (cause instanceof TooLongFrameException) {
            return diagnostic(OnionProbeSession.State.FAILED, OnionProbeSession.Reason.RESPONSE_LIMIT);
        }
        return failed();
    }
    private boolean allowed() { return !cancelled && stillReady.getAsBoolean(); }
    private boolean deadlineExpired() {
        return started && System.nanoTime() - deadlineNanos >= 0;
    }
    private void finish(OnionProbeSession.Result value) {
        if (!allowed()) value = diagnostic(OnionProbeSession.State.CANCELLED, OnionProbeSession.Reason.CANCELLED);
        else if (value.state != OnionProbeSession.State.CANCELLED && deadlineExpired()) {
            value = diagnostic(OnionProbeSession.State.TIMED_OUT, OnionProbeSession.Reason.DEADLINE);
        }
        completed.complete(value);
        TorSocketChannel active = channel;
        if (active != null) active.abort();
    }
    private OnionProbeSession.Result exchange(long deadline) {
        OioEventLoopGroup group = null;
        try {
            if (!allowed()) return diagnostic(OnionProbeSession.State.CANCELLED, OnionProbeSession.Reason.CANCELLED);
            // Construction, socket I/O, waits, and cleanup never occupy the Tor control or UI thread.
            group = new TorSocketEventLoopGroup();
            Bootstrap bootstrap = new Bootstrap().group(group)
                    .channelFactory(() -> new TorSocketChannel(endpoint, this::allowed, deadline, sockets))
                    .resolver(NoopAddressResolverGroup.INSTANCE)
                    .handler(new ChannelInitializer<TorSocketChannel>() {
                        @Override protected void initChannel(TorSocketChannel ch) {
                            channel = ch; // Publish before the first connect; cancellation owns this channel.
                            if (!allowed() || completed.isDone()) { ch.close(); return; }
                            if (deadlineExpired()) {
                                finish(diagnostic(OnionProbeSession.State.TIMED_OUT, OnionProbeSession.Reason.DEADLINE));
                                return;
                            }
                            Socks5ProxyHandler socks = new Socks5ProxyHandler(endpoint);
                            // Onion descriptor/rendezvous work shares the hard overall deadline.
                            // A second 10-second SOCKS timer used to mask timeouts as generic failures.
                            socks.setConnectTimeoutMillis(0);
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                    transition(OnionProbeSession.Stage.SOCKS_GREETING);
                                    super.channelActive(ctx);
                                }
                            });
                            ch.pipeline().addLast(socks);
                            // Observe already-decoded control messages, never raw buffers or addresses.
                            // Netty keeps ownership of validation, codec transitions and CONNECT bytes.
                            ch.pipeline().addBefore(ch.pipeline().context(socks).name(), "probe-socks-progress",
                                    new ChannelDuplexHandler() {
                                @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                                    if (message instanceof Socks5CommandRequest) {
                                        socksStatus = -1;
                                        transition(OnionProbeSession.Stage.ONION_CONNECT);
                                    }
                                    ctx.write(message, promise);
                                }
                                @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                                    if (message instanceof Socks5InitialResponse) {
                                        Socks5InitialResponse response = (Socks5InitialResponse) message;
                                        if (response.decoderResult().isSuccess() && response.authMethod() != Socks5AuthMethod.NO_AUTH) {
                                            socksStatus = response.authMethod().byteValue() & 255;
                                            socksFailure = OnionProbeSession.Reason.SOCKS_AUTH_REJECTED;
                                        }
                                    } else if (message instanceof Socks5CommandResponse) {
                                        Socks5CommandResponse response = (Socks5CommandResponse) message;
                                        if (response.decoderResult().isSuccess()) {
                                            socksStatus = response.status().byteValue() & 255;
                                            if (response.status() != Socks5CommandStatus.SUCCESS) {
                                                socksFailure = OnionProbeSession.Reason.SOCKS_CONNECT_REJECTED;
                                            }
                                        }
                                    }
                                    ctx.fireChannelRead(message);
                                }
                            });
                            ch.pipeline().addLast(new HttpClientCodec(4096, 8192, 8192));
                            ch.pipeline().addLast(new HttpObjectAggregator(MAX_BODY_BYTES));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                                @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                                    if (!allowed()) {
                                        finish(diagnostic(OnionProbeSession.State.CANCELLED, OnionProbeSession.Reason.CANCELLED));
                                    } else if (!response.decoderResult().isSuccess()) {
                                        finish(diagnostic(OnionProbeSession.State.FAILED, OnionProbeSession.Reason.INVALID_RESPONSE));
                                    } else if (response.status().code() >= 200 && response.status().code() <= 599) {
                                        // Only numeric metadata escapes. Never decode/display/store the body or headers.
                                        finish(new OnionProbeSession.Result(OnionProbeSession.State.RESPONSE,
                                                response.status().code(), response.content().readableBytes(), stage,
                                                OnionProbeSession.Reason.NONE, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos), socksStatus));
                                    } else if (response.status().code() == 101 || response.status().code() > 599) {
                                        finish(diagnostic(OnionProbeSession.State.FAILED, OnionProbeSession.Reason.INVALID_RESPONSE));
                                    }
                                }
                                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    finish(failed(cause));
                                }
                                @Override public void channelInactive(ChannelHandlerContext ctx) {
                                    finish(failed());
                                }
                            });
                            socks.connectFuture().addListener(connected -> {
                                if (!allowed() || completed.isDone()) {
                                    finish(diagnostic(OnionProbeSession.State.CANCELLED, OnionProbeSession.Reason.CANCELLED));
                                } else if (deadlineExpired()) {
                                    finish(diagnostic(OnionProbeSession.State.TIMED_OUT, OnionProbeSession.Reason.DEADLINE));
                                } else if (!connected.isSuccess()) {
                                    finish(failed(connected.cause()));
                                } else {
                                    transition(OnionProbeSession.Stage.HTTP_WRITE);
                                    DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                                            HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
                                    request.headers().set(HttpHeaderNames.HOST, HOST);
                                    request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                                    request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                                    ch.writeAndFlush(request).addListener(written -> {
                                        if (!written.isSuccess()) finish(failed());
                                        else transition(OnionProbeSession.Stage.HTTP_READ);
                                    });
                                }
                            });
                        }
                    });
            if (!allowed()) return diagnostic(OnionProbeSession.State.CANCELLED, OnionProbeSession.Reason.CANCELLED);
            if (System.nanoTime() >= deadline) return diagnostic(OnionProbeSession.State.TIMED_OUT, OnionProbeSession.Reason.DEADLINE);
            // createUnresolved + NoopAddressResolverGroup passes a DOMAIN address to SOCKS, never local DNS.
            transition(OnionProbeSession.Stage.LOCAL_CONNECT);
            ChannelFuture connection = bootstrap.connect(InetSocketAddress.createUnresolved(HOST, 80));
            channel = (TorSocketChannel)connection.channel(); // Retain even if registration/init fails.
            connection.addListener(connected -> {
                if (!connected.isSuccess()) finish(failed(connected.cause()));
            });
            while (true) {
                if (!allowed()) return diagnostic(OnionProbeSession.State.CANCELLED, OnionProbeSession.Reason.CANCELLED);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return diagnostic(OnionProbeSession.State.TIMED_OUT, OnionProbeSession.Reason.DEADLINE);
                try {
                    return completed.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException pending) {
                    // Also revoke stale readiness if the Android main thread/control poll is stalled.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return diagnostic(OnionProbeSession.State.CANCELLED, OnionProbeSession.Reason.CANCELLED);
        } catch (ExecutionException | RuntimeException | LinkageError unavailable) {
            return failed();
        } finally {
            // Reject a late successful SOCKS callback before closing; it must never enqueue a GET.
            cancelled = true;
            TorSocketChannel.shutdown(channel, group);
        }
    }
}
