package dev.gyeopdam.embeddedtor;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.PausedDeregisterOioGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic host-only event-loop lifecycle regression; creates no sockets. */
@SuppressWarnings("deprecation")
public class TorSocketShutdownRaceTest {
    private static final TorSocketEndpoint ENDPOINT =
            new TorSocketEndpoint("/fixture/tor-socks/s", 10001, 22002);

    @Test public void fixtureReproducesStockGroupMissingActiveToIdleTransfer() throws Exception {
        PausedDeregisterOioGroup group = new PausedDeregisterOioGroup();
        TorSocketChannel channel = channel();
        try {
            assertTrue(group.register(channel).await(2, TimeUnit.SECONDS));
            assertTrue(channel.isRegistered());
            assertTrue(channel.close().await(2, TimeUnit.SECONDS));
            assertTrue(group.awaitTransition());
            group.moveToIdleAfterShutdownScans();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            // These assertions need no sleep: insertion occurs before shutdown returns.
            assertFalse(group.ownedChild().isShuttingDown());
            assertFalse(group.terminationFuture().isDone());
        } finally {
            group.releaseTransition();
            rescue(group.ownedChild(), group);
        }
    }

    @Test public void exactOwnerTerminatesWhenGroupMissesActiveToIdleTransfer() throws Exception {
        PausedDeregisterOioGroup group = new PausedDeregisterOioGroup();
        TorSocketChannel channel = channel();
        try {
            assertTrue(group.register(channel).await(2, TimeUnit.SECONDS));
            assertTrue(channel.isRegistered());
            assertTrue(channel.close().await(2, TimeUnit.SECONDS));
            assertTrue(group.awaitTransition());
            group.moveToIdleAfterShutdownScans();
            // Fixed helper releases the transition by addressing the exact owner.
            // Group-only cleanup instead misses the child and never terminates.
            shutdownAndVerify(channel, group);
        } finally {
            group.releaseTransition();
            rescue(group.ownedChild(), group);
        }
    }

    @Test public void connectFutureRetainsOwnerWhenInitializerClosesBeforePublication() throws Exception {
        OioEventLoopGroup group = new TorSocketEventLoopGroup();
        AtomicBoolean initializerEntered = new AtomicBoolean();
        TorSocketChannel retained = null;
        try {
            ChannelFuture connecting = new Bootstrap().group(group)
                    .channelFactory(TorSocketShutdownRaceTest::channel)
                    .resolver(NoopAddressResolverGroup.INSTANCE)
                    .handler(new ChannelInitializer<TorSocketChannel>() {
                        @Override protected void initChannel(TorSocketChannel channel) {
                            initializerEntered.set(true);
                            // Mirrors a rejected/cancelled initializer before publication.
                            channel.close();
                        }
                    }).connect(ENDPOINT);
            retained = (TorSocketChannel) connecting.channel();
            assertNotNull(retained.eventLoop());
            assertTrue(connecting.await(2, TimeUnit.SECONDS));
            assertTrue(initializerEntered.get());
            assertFalse(connecting.isSuccess());
            shutdownAndVerify(retained, group);
        } finally {
            rescue(retained == null ? null : retained.eventLoop(), group);
        }
    }

    @Test public void unregisteredChannelCleanupDoesNotRequireAnEventLoop() throws Exception {
        OioEventLoopGroup group = new TorSocketEventLoopGroup();
        TorSocketChannel channel = channel();
        try {
            TorSocketChannel.shutdown(channel, group);
            assertFalse(channel.isOpen());
            assertTrue(group.isTerminated());
            assertTrue(group.terminationFuture().isSuccess());
        } finally {
            // No native I/O ever existed, so this direct close is safe for fixture rescue.
            channel.unsafe().closeForcibly();
            rescue(null, group);
        }
    }

    @Test public void guardedOwnerCannotLoseShutdownWakeupDuringDeregistrationDrain() throws Exception {
        OioEventLoopGroup group = new TorSocketEventLoopGroup();
        TorSocketChannel channel = channel();
        CountDownLatch draining = new CountDownLatch(1), releaseDrain = new CountDownLatch(1);
        EventLoop owner = null;
        try {
            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override public void channelUnregistered(ChannelHandlerContext context) {
                    // ThreadPerChannelEventLoop drains this queued task only after it
                    // chose the not-shutting-down deregistration branch in run().
                    context.executor().execute(() -> {
                        draining.countDown();
                        try {
                            if (!releaseDrain.await(3, TimeUnit.SECONDS)) {
                                throw new AssertionError("release deregistration drain");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    });
                    context.fireChannelUnregistered();
                }
            });
            assertTrue(group.register(channel).await(2, TimeUnit.SECONDS));
            owner = channel.eventLoop();
            assertTrue(channel.close().await(2, TimeUnit.SECONDS));
            assertTrue(draining.await(2, TimeUnit.SECONDS));
            // The call returns after WAKEUP_TASK is enqueued. Releasing the task
            // makes runAllTasks consume that wakeup before the next takeTask().
            owner.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            releaseDrain.countDown();
            assertTrue("Shutdown wakeup was consumed before a blocking takeTask",
                    owner.terminationFuture().await(2, TimeUnit.SECONDS));
            assertTrue(owner.isTerminated());
        } finally {
            releaseDrain.countDown();
            rescue(owner, group);
        }
    }

    private static TorSocketChannel channel() {
        TorSocketChannel channel = new TorSocketChannel(ENDPOINT, () -> true,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5), endpoint -> {
                    throw new AssertionError("Lifecycle fixture must never create a socket");
                });
        channel.config().setAutoRead(false);
        return channel;
    }

    private static void shutdownAndVerify(TorSocketChannel channel, OioEventLoopGroup group)
            throws Exception {
        EventLoop owner = channel.eventLoop();
        ExecutorService cleanup = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "tor-shutdown-regression");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // A regressed production helper fails this bounded test rather than hanging JUnit.
            cleanup.submit(() -> TorSocketChannel.shutdown(channel, group)).get(2, TimeUnit.SECONDS);
            assertTrue(channel.closeFuture().isDone());
            assertTrue(owner.isTerminated());
            assertTrue(group.isTerminated());
            assertTrue(group.terminationFuture().isSuccess());
        } finally {
            rescue(owner, group);
            cleanup.shutdownNow();
            assertTrue(cleanup.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static void rescue(EventLoop owner, OioEventLoopGroup group) throws InterruptedException {
        if (owner != null) {
            // Test-only rescue: a mutation of the wakeup guard must fail assertions
            // above without leaving a vendor thread parked after the test finishes.
            if (owner.isShuttingDown() && !owner.isTerminated()) {
                try { owner.execute(() -> { }); }
                catch (RejectedExecutionException alreadyTerminated) { /* A racing termination is safe. */ }
            }
            assertTrue(owner.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(2, TimeUnit.SECONDS));
        }
        assertTrue(group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(2, TimeUnit.SECONDS));
    }
}
