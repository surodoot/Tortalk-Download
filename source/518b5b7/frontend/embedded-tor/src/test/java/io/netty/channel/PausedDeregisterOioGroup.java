package io.netty.channel;

import io.netty.channel.oio.OioEventLoopGroup;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-only scheduling seam for Netty's active-to-idle child transition. */
@SuppressWarnings("deprecation")
public final class PausedDeregisterOioGroup extends OioEventLoopGroup {
    private final CountDownLatch betweenCollections = new CountDownLatch(1);
    private final CountDownLatch releaseTransition = new CountDownLatch(1);
    private final CountDownLatch enteredIdle = new CountDownLatch(1);
    private final AtomicBoolean pauseOnce = new AtomicBoolean(true);
    private volatile boolean releaseAtTerminationCheck;
    private volatile EventLoop ownedChild;

    public PausedDeregisterOioGroup() { super(1); }

    @Override protected EventLoop newChild(Object... args) {
        EventLoop child = new ThreadPerChannelEventLoop(this) {
            @Override protected Runnable takeTask() {
                // Keep the separate lost-wakeup bug out of this collection-transfer fixture.
                return isShuttingDown() ? null : super.takeTask();
            }
            @Override public io.netty.util.concurrent.Future<?> shutdownGracefully(
                    long quietPeriod, long timeout, TimeUnit unit) {
                io.netty.util.concurrent.Future<?> completion =
                        super.shutdownGracefully(quietPeriod, timeout, unit);
                // The production fix addresses this exact child, which must release
                // the paused transition without relying on the group to discover it.
                releaseTransition.countDown();
                return completion;
            }
            @Override protected void deregister() {
                if (!pauseOnce.compareAndSet(true, false)) { super.deregister(); return; }
                // Reproduce the first operation of the pinned Netty implementation,
                // pausing before its second operation inserts this child into idle.
                ThreadPerChannelEventLoopGroup parent = PausedDeregisterOioGroup.this;
                parent.activeChildren.remove(this);
                betweenCollections.countDown();
                await(releaseTransition, "release child transition");
                super.deregister();
                enteredIdle.countDown();
            }
        };
        ownedChild = child;
        return child;
    }

    public boolean awaitTransition() throws InterruptedException {
        return betweenCollections.await(2, TimeUnit.SECONDS);
    }

    /** The stock shutdown will scan empty collections, then find the live idle child. */
    public void moveToIdleAfterShutdownScans() { releaseAtTerminationCheck = true; }

    @Override public boolean isTerminated() {
        if (releaseAtTerminationCheck) {
            releaseAtTerminationCheck = false;
            releaseTransition.countDown();
            await(enteredIdle, "child entered idle set");
        }
        return super.isTerminated();
    }

    public EventLoop ownedChild() { return ownedChild; }
    public void releaseTransition() { releaseTransition.countDown(); }

    private static void await(CountDownLatch latch, String stage) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError(stage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(stage, interrupted);
        }
    }
}
