package dev.gyeopdam.embeddedtor;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** One bounded HTTP/JSON exchange over an expiring embedded-Tor lease; no retries or redirects. */
final class OnionApiTransport {
    static final int DEADLINE_MS = 30_000;
    enum Error { NONE, CANCELLED, DEADLINE, NETWORK, INVALID_RESPONSE, RESPONSE_LIMIT }
    static final class Result {
        final int status;
        final byte[] body;
        final Error error;
        Result(int status, byte[] body, Error error) { this.status = status; this.body = body; this.error = error; }
        static Result error(Error error) { return new Result(0, new byte[0], error); }
        @Override public String toString() { return "OnionApiResult(" + error.name() + ")"; }
    }
    private final TorSocketEndpoint endpoint;
    private final TorSocketIo.Factory sockets;
    private final long deadlineNanos;
    private final BooleanSupplier ready;
    private final CompletableFuture<Result> result = new CompletableFuture<>();
    private volatile TorSocketChannel channel;
    private volatile boolean cancelled;
    OnionApiTransport(TorSocketEndpoint endpoint, BooleanSupplier ready) { this(endpoint, ready, DEADLINE_MS); }
    OnionApiTransport(TorSocketEndpoint endpoint, BooleanSupplier ready, int deadlineMillis) {
        this(endpoint, ready, deadlineMillis, AndroidTorSocket::new);
    }
    OnionApiTransport(TorSocketEndpoint endpoint, BooleanSupplier ready, int deadlineMillis, TorSocketIo.Factory sockets) {
        if (endpoint == null || deadlineMillis < 1 || deadlineMillis > DEADLINE_MS) throw new IllegalArgumentException("INVALID_LIMIT");
        this.endpoint = endpoint; this.sockets = sockets; this.ready = ready;
        this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
    }
    void cancel() { cancelled = true; complete(Result.error(Error.CANCELLED)); }
    private boolean allowed() { return !cancelled && ready.getAsBoolean(); }
    private void complete(Result value) {
        if (!allowed()) value = Result.error(Error.CANCELLED);
        else if (System.nanoTime() >= deadlineNanos) value = Result.error(Error.DEADLINE);
        result.complete(value); TorSocketChannel c = channel; if (c != null) c.abort();
    }
    Result execute(OnionApiRequest request) {
        final long deadline = deadlineNanos;
        OioEventLoopGroup group = null;
        try {
            if (!allowed()) return Result.error(Error.CANCELLED);
            group = new TorSocketEventLoopGroup();
            Bootstrap bootstrap = new Bootstrap().group(group)
                    .channelFactory(() -> new TorSocketChannel(endpoint, this::allowed, deadline, sockets))
                    .resolver(NoopAddressResolverGroup.INSTANCE)
                    .handler(new ChannelInitializer<TorSocketChannel>() {
                @Override protected void initChannel(TorSocketChannel ch) {
                    channel = ch;
                    if (!allowed()) { cancel(); return; }
                    if (System.nanoTime() >= deadline) { complete(Result.error(Error.DEADLINE)); return; }
                    Socks5ProxyHandler socks = new Socks5ProxyHandler(endpoint); socks.setConnectTimeoutMillis(0);
                    ch.pipeline().addLast(socks, new HttpClientCodec(4096,8192,8192),
                            new HttpObjectAggregator(OnionApiRequest.MAX_RESPONSE_BYTES),
                            new SimpleChannelInboundHandler<FullHttpResponse>() {
                        @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                            if (!allowed()) complete(Result.error(Error.CANCELLED));
                            else if (System.nanoTime() >= deadline) complete(Result.error(Error.DEADLINE));
                            else if (!response.decoderResult().isSuccess() || response.status().code() < 200
                                    || response.status().code() > 599 || response.status().code() == 101) complete(Result.error(Error.INVALID_RESPONSE));
                            else {
                                byte[] bytes = new byte[response.content().readableBytes()]; response.content().readBytes(bytes);
                                complete(new Result(response.status().code(), bytes, Error.NONE));
                            }
                        }
                        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { complete(Result.error(cause instanceof TooLongFrameException ? Error.RESPONSE_LIMIT : Error.NETWORK)); }
                        @Override public void channelInactive(ChannelHandlerContext ctx) { complete(Result.error(Error.NETWORK)); }
                    });
                    socks.connectFuture().addListener(connected -> {
                        if (!allowed() || result.isDone()) { cancel(); return; }
                        if (System.nanoTime() >= deadline) { complete(Result.error(Error.DEADLINE)); return; }
                        if (!connected.isSuccess()) { complete(Result.error(Error.NETWORK)); return; }
                        DefaultFullHttpRequest message = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                                HttpMethod.valueOf(request.method), request.path, Unpooled.wrappedBuffer(request.body()));
                        message.headers().set(HttpHeaderNames.HOST, request.host);
                        message.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        message.headers().set(HttpHeaderNames.CONTENT_LENGTH, message.content().readableBytes());
                        message.headers().set(HttpHeaderNames.ACCEPT, "application/json");
                        if (message.content().readableBytes() > 0) message.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                        if (!request.sessionToken().isEmpty()) message.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + request.sessionToken());
                        if (!request.deviceToken().isEmpty()) message.headers().set("X-TorTalk-Device-Token", request.deviceToken());
                        ch.writeAndFlush(message).addListener(written -> { if (!written.isSuccess()) complete(Result.error(Error.NETWORK)); });
                    });
                }
            });
            if (!allowed()) return Result.error(Error.CANCELLED);
            if (System.nanoTime() >= deadline) return Result.error(Error.DEADLINE);
            ChannelFuture connection = bootstrap.connect(InetSocketAddress.createUnresolved(request.host, 80));
            channel = (TorSocketChannel)connection.channel(); // Retain even if registration/init fails.
            connection.addListener(connected -> {
                if (!connected.isSuccess()) complete(Result.error(Error.NETWORK));
            });
            while (true) {
                if (!allowed()) return Result.error(Error.CANCELLED);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return Result.error(Error.DEADLINE);
                try { return result.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS); }
                catch (TimeoutException pending) { /* refresh the ready lease */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return Result.error(Error.CANCELLED);
        } catch (Exception | LinkageError unavailable) { return Result.error(Error.NETWORK); }
        finally {
            cancelled = true;
            TorSocketChannel.shutdown(channel, group);
            request.destroyBody();
        }
    }
}
