package dev.gyeopdam.embeddedtor;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.channel.ThreadPerChannelEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual channel/owner lifecycle with a paused host task queue; creates no sockets. */
@SuppressWarnings("deprecation")
public class TorSocketAbortSubmissionTest {
    private static final TorSocketEndpoint ENDPOINT =
            new TorSocketEndpoint("/fixture/tor-socks/s", 10001, 22002);
    private static final ThreadLocal<QueueHarness> CONSTRUCTION = new ThreadLocal<>();

    @Test public void cleanupWaitsForSingleCloseSubmissionAndReleasesQueuedPayload() throws Exception {
        QueueHarness harness = new QueueHarness();
        PausedOfferGroup group = new PausedOfferGroup(harness);
        FakeIo io = new FakeIo();
        TorSocketChannel channel = channel(io);
        CountDownLatch ownerBlocked = new CountDownLatch(1), releaseOwner = new CountDownLatch(1);
        AtomicReference<Throwable> abortFailure = new AtomicReference<>(), cleanupFailure = new AtomicReference<>();
        Thread aborter = null, cleaner = null;
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{9, 8, 7, 6});
        try {
            group.register(channel).sync();
            channel.eventLoop().submit(() -> { channel.doConnect(ENDPOINT, null); return null; })
                    .get(2, TimeUnit.SECONDS);
            channel.eventLoop().execute(() -> { ownerBlocked.countDown(); await(releaseOwner); });
            assertTrue(ownerBlocked.await(2, TimeUnit.SECONDS));
            ChannelFuture write = channel.writeAndFlush(payload);

            harness.pauseClose.set(true);
            aborter = thread("first-abort-submitter", channel::abort, abortFailure);
            aborter.start();
            assertTrue(harness.beforeCloseOffer.await(2, TimeUnit.SECONDS));
            assertFalse("Revocation must precede close-task publication", channel.isActive());
            assertTrue(io.shutdowns.get() > 0);
            assertEquals(0, io.writes.get());
            assertEquals(0, io.closes.get());
            // The assertion must still reject a real queued close task, not only an
            // arbitrary application Runnable. Inspect a separate queue; never run it.
            Queue<Runnable> forbiddenClose = new LinkedBlockingQueue<>();
            forbiddenClose.add(harness.closeTask);
            assertThrows(AssertionError.class, () -> assertNoApplicationTasks(forbiddenClose));

            cleaner = thread("concurrent-owner-cleanup", () -> TorSocketChannel.shutdown(channel, group), cleanupFailure);
            cleaner.start();
            Thread cleanupThread = cleaner;
            awaitCondition(() -> cleanupThread.getState() == Thread.State.BLOCKED
                    || harness.owner.isShuttingDown());
            assertEquals("Cleanup must wait for the first close submission", Thread.State.BLOCKED, cleaner.getState());
            assertFalse("Owner termination must not overtake close submission", harness.owner.isShuttingDown());

            harness.allowCloseOffer.countDown();
            aborter.join(2000);
            assertFalse(aborter.isAlive());
            assertNull(abortFailure.get());
            releaseOwner.countDown();
            cleaner.join(2000);
            assertFalse("Cleanup must terminate without leaving an owner thread", cleaner.isAlive());
            assertNull(cleanupFailure.get());
            assertTrue(write.isDone());
            assertFalse(write.isSuccess());
            assertEquals("Queued payload ownership must be released", 0, payload.refCnt());
            assertEquals(0, io.writes.get());
            assertEquals(1, io.closes.get());
            assertSame(io.ioOwner, io.closeOwner);
            assertEquals(1, harness.closeOffers.get());
            assertTrue(harness.owner.isTerminated());
            assertTrue(group.isTerminated());
            assertNoApplicationTasks(harness.queue);

            for (int i = 0; i < 3; i++) channel.abort();
            assertEquals("Late abort must not enqueue another close", 1, harness.closeOffers.get());
            assertNoApplicationTasks(harness.queue);
            assertEquals(1, io.closes.get());
        } finally {
            harness.allowCloseOffer.countDown();
            releaseOwner.countDown();
            if (aborter != null) aborter.join(2000);
            rescue(harness.owner, group);
            if (cleaner != null) cleaner.join(2000);
            if (payload.refCnt() > 0) payload.release();
        }
    }

    @Test public void ownerCloseFutureMayReenterAbortWithoutDuplicateCloseOrDeadlock() throws Exception {
        OioEventLoopGroup group = new TorSocketEventLoopGroup();
        FakeIo io = new FakeIo();
        TorSocketChannel channel = channel(io);
        AtomicInteger reentries = new AtomicInteger();
        try {
            group.register(channel).sync();
            channel.eventLoop().submit(() -> { channel.doConnect(ENDPOINT, null); return null; })
                    .get(2, TimeUnit.SECONDS);
            channel.closeFuture().addListener(closed -> { reentries.incrementAndGet(); channel.abort(); });
            channel.eventLoop().submit(channel::abort).get(2, TimeUnit.SECONDS);
            TorSocketChannel.shutdown(channel, group);
            assertEquals(1, reentries.get());
            assertEquals(1, io.closes.get());
            assertSame(io.ioOwner, io.closeOwner);
            assertEquals(0, io.writes.get());
            assertTrue(group.isTerminated());
        } finally { rescue(channel.eventLoop(), group); }
    }

    @Test public void firstAbortAfterNaturalOwnerCloseDoesNotSubmitAnotherTask() throws Exception {
        QueueHarness harness = new QueueHarness();
        PausedOfferGroup group = new PausedOfferGroup(harness);
        FakeIo io = new FakeIo();
        TorSocketChannel channel = channel(io);
        CountDownLatch ownerClosed = new CountDownLatch(1), releaseOwner = new CountDownLatch(1);
        try {
            group.register(channel).sync();
            channel.eventLoop().submit(() -> { channel.doConnect(ENDPOINT, null); return null; })
                    .get(2, TimeUnit.SECONDS);
            channel.eventLoop().execute(() -> {
                channel.close(); // Natural owner close, with no preceding abort.
                ownerClosed.countDown();
                await(releaseOwner);
            });
            assertTrue(ownerClosed.await(2, TimeUnit.SECONDS));
            assertEquals(1, io.closes.get());
            int offersBeforeLateAbort = harness.allOffers.get();
            channel.abort();
            assertEquals("A closed channel must not submit its first abort task",
                    offersBeforeLateAbort, harness.allOffers.get());
            assertEquals(1, io.closes.get());
            assertEquals(0, io.writes.get());
            releaseOwner.countDown();
            TorSocketChannel.shutdown(channel, group);
            assertTrue(group.isTerminated());
            assertNoApplicationTasks(harness.queue);
        } finally {
            releaseOwner.countDown();
            rescue(harness.owner, group);
        }
    }

    @Test public void queueAssertionRejectsOrdinaryApplicationRunnable() {
        Queue<Runnable> tasks = new LinkedBlockingQueue<>();
        tasks.add(() -> { });
        assertThrows(AssertionError.class, () -> assertNoApplicationTasks(tasks));
    }

    @Test public void exactWakeupOfferedAfterOwnerTerminationIsNotAnApplicationTask() throws Exception {
        QueueHarness harness = new QueueHarness();
        PausedOfferGroup group = new PausedOfferGroup(harness);
        FakeIo io = new FakeIo();
        TorSocketChannel channel = channel(io);
        CountDownLatch ownerBlocked = new CountDownLatch(1), releaseOwner = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread submitter = null;
        try {
            group.register(channel).sync();
            channel.eventLoop().submit(() -> { channel.doConnect(ENDPOINT,null); return null; }).get(2,TimeUnit.SECONDS);
            channel.eventLoop().execute(() -> { ownerBlocked.countDown(); await(releaseOwner); });
            assertTrue(ownerBlocked.await(2,TimeUnit.SECONDS));
            harness.pauseWakeup.set(true);
            submitter = thread("late-wakeup-submitter",
                    () -> harness.owner.shutdownGracefully(0,1,TimeUnit.SECONDS), failure);
            submitter.start();
            assertTrue(harness.beforeWakeupOffer.await(2,TimeUnit.SECONDS));
            assertTrue(harness.owner.isShuttingDown());
            releaseOwner.countDown();
            assertTrue(harness.owner.terminationFuture().await(2,TimeUnit.SECONDS));
            assertTrue(harness.queue.isEmpty());
            harness.allowWakeupOffer.countDown();
            submitter.join(2000);
            assertFalse(submitter.isAlive());
            assertNull(failure.get());
            assertEquals("The delayed shutdown caller offers one fixed wakeup",1,harness.queue.size());
            // Netty's pinned pollTaskFrom skips only object identity == WAKEUP_TASK.
            // No class-name match, arbitrary no-op allowance, reflection, or task execution.
            assertNoApplicationTasks(harness.queue);
            assertEquals(1,io.closes.get());
            assertEquals(0,io.writes.get());
        } finally {
            releaseOwner.countDown();harness.allowWakeupOffer.countDown();
            if(submitter!=null)submitter.join(2000);
            rescue(harness.owner,group);
        }
    }

    private static void assertNoApplicationTasks(Queue<Runnable> tasks) {
        // Call only after the owner has terminated and external submitters have joined.
        // The framework can publish shutdown state before offering its fixed wakeup.
        assertTrue("Unexpected application task after owner termination",
                QueueOwner.pollApplicationTask(tasks)==null);
        assertTrue("Queue must be physically empty after exact-sentinel removal",tasks.isEmpty());
    }

    private static TorSocketChannel channel(FakeIo io) {
        TorSocketChannel channel = new TorSocketChannel(ENDPOINT, () -> true,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10), endpoint -> io);
        channel.config().setAutoRead(false);
        return channel;
    }

    private static Thread thread(String name, Runnable action, AtomicReference<Throwable> failure) {
        return new Thread(() -> { try { action.run(); } catch (Throwable thrown) { failure.set(thrown); } }, name);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture barrier timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue("Concurrent cleanup did not reach the controlled boundary", condition.getAsBoolean());
    }

    private static void rescue(EventLoop owner, OioEventLoopGroup group) throws InterruptedException {
        if (owner != null) {
            if (owner.isShuttingDown() && !owner.isTerminated()) {
                try { owner.execute(() -> { }); } catch (RejectedExecutionException terminated) { }
            }
            assertTrue(owner.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(2, TimeUnit.SECONDS));
        }
        assertTrue(group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(2, TimeUnit.SECONDS));
    }

    private static final class QueueHarness {
        int queues;
        volatile TorSocketEventLoopGroup.Owner owner;
        volatile LinkedBlockingQueue<Runnable> queue;
        volatile Class<?> closeTaskClass;
        volatile Runnable closeTask;
        final AtomicBoolean pauseClose = new AtomicBoolean();
        final AtomicBoolean pauseWakeup = new AtomicBoolean();
        final AtomicInteger closeOffers = new AtomicInteger();
        final AtomicInteger allOffers = new AtomicInteger();
        final CountDownLatch beforeCloseOffer = new CountDownLatch(1), allowCloseOffer = new CountDownLatch(1);
        final CountDownLatch beforeWakeupOffer = new CountDownLatch(1), allowWakeupOffer = new CountDownLatch(1);
    }

    private static final class PausedOfferGroup extends OioEventLoopGroup {
        private final QueueHarness harness;
        PausedOfferGroup(QueueHarness harness) { super(1); this.harness = harness; }
        @Override protected EventLoop newChild(Object... args) {
            CONSTRUCTION.set(harness);
            try { return harness.owner = new QueueOwner(this); }
            finally { CONSTRUCTION.remove(); }
        }
    }

    private static final class QueueOwner extends TorSocketEventLoopGroup.Owner {
        QueueOwner(ThreadPerChannelEventLoopGroup parent) { super(parent); }
        static Runnable pollApplicationTask(Queue<Runnable> tasks) { return pollTaskFrom(tasks); }
        @Override protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
            QueueHarness harness = CONSTRUCTION.get();
            if (harness.queues++ != 0) return new LinkedBlockingQueue<>(maxPendingTasks);
            harness.queue = new LinkedBlockingQueue<Runnable>(maxPendingTasks) {
                @Override public boolean offer(Runnable task) {
                    harness.allOffers.incrementAndGet();
                    if(Thread.currentThread().getName().equals("late-wakeup-submitter")
                            &&harness.pauseWakeup.compareAndSet(true,false)) {
                        harness.beforeWakeupOffer.countDown();
                        await(harness.allowWakeupOffer);
                    }
                    if (Thread.currentThread().getName().equals("first-abort-submitter")
                            && harness.pauseClose.compareAndSet(true, false)) {
                        harness.closeTaskClass = task.getClass();
                        harness.closeTask = task;
                        harness.closeOffers.incrementAndGet();
                        harness.beforeCloseOffer.countDown();
                        await(harness.allowCloseOffer);
                    } else if (task.getClass() == harness.closeTaskClass) {
                        harness.closeOffers.incrementAndGet();
                    }
                    return super.offer(task);
                }
            };
            return harness.queue;
        }
    }

    private static final class FakeIo implements TorSocketIo {
        final AtomicInteger writes = new AtomicInteger(), shutdowns = new AtomicInteger(), closes = new AtomicInteger();
        volatile Thread ioOwner, closeOwner;
        public void connect(int timeoutMillis) { ioOwner = Thread.currentThread(); }
        public int peerUid() { return ENDPOINT.uid; }
        public int peerPid() { return ENDPOINT.pid; }
        public int read(byte[] bytes, int length, int waitMillis) { return 0; }
        public boolean awaitWritable(int waitMillis) { return true; }
        public int write(byte[] bytes, int length) { writes.incrementAndGet(); return length; }
        public void shutdown() { shutdowns.incrementAndGet(); }
        public void close() { closeOwner = Thread.currentThread(); closes.incrementAndGet(); }
    }
}
