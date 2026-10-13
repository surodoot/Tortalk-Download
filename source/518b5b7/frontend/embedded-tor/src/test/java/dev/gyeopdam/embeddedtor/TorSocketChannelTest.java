package dev.gyeopdam.embeddedtor;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.oio.OioEventLoopGroup;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real adapter and Netty loop, explicit peer/syscall doubles. No Android kernel claims. */
@SuppressWarnings("deprecation")
public class TorSocketChannelTest {
    private static final TorSocketEndpoint ENDPOINT = new TorSocketEndpoint("/fixture/tor-socks/s", 10001, 22002);
    @Test public void endpointRejectsRelativeAbstractLongAndTraversalPaths() {
        for (String path : new String[]{"relative", "@abstract", "/a/../b", "/a/./b", "/a//b", "/a/", "/a\n/s", "/"+new String(new char[108]).replace('\0','a')}) {
            assertThrows(IllegalArgumentException.class, () -> new TorSocketEndpoint(path,10001,22002));
        }
        assertThrows(IllegalArgumentException.class, () -> new TorSocketEndpoint("/a/s",10001,0));
        assertEquals("TorUnixEndpoint",ENDPOINT.toString());
    }
    @Test public void foreignUidAndWrongIncarnationPidNeverWrite() throws Exception {
        for (boolean wrongUid : new boolean[]{true,false}) {
            FakeIo io = new FakeIo(); if(wrongUid)io.uid++;else io.pid++;
            try(Fixture fixture = new Fixture(io,new AtomicBoolean(true),2000)) {
                assertThrows(ExecutionException.class,fixture::connect);
                assertEquals(0,io.writes.get()); assertTrue(io.closed);
            }
        }
    }
    @Test public void validPeerProofPrecedesAllWritesAndPartialSendsKeepBytes() throws Exception {
        FakeIo io = new FakeIo(); io.maximumWrite = 2;
        try(Fixture fixture = new Fixture(io,new AtomicBoolean(true),2000)) {
            fixture.connect(); ChannelFuture sent = fixture.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1,2,3,4,5}));
            assertTrue(sent.await(2,TimeUnit.SECONDS)); assertTrue(sent.isSuccess());
            assertArrayEquals(new byte[]{1,2,3,4,5},io.output.toByteArray()); assertEquals(3,io.writes.get());
            assertTrue(io.identityRead);
        }
    }
    @Test public void readinessLostWhileAwaitingWritablePreventsFirstSend() throws Exception {
        AtomicBoolean ready = new AtomicBoolean(true); FakeIo io = new FakeIo();
        io.afterWait = () -> ready.set(false);
        try(Fixture fixture = new Fixture(io,ready,2000)) {
            fixture.connect(); ChannelFuture sent = fixture.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{7}));
            assertTrue(sent.await(2,TimeUnit.SECONDS)); assertFalse(sent.isSuccess()); assertEquals(0,io.writes.get());
        }
    }
    @Test public void expiredWritableWaitCannotSendWithoutAnyWatchdog() throws Exception {
        FakeIo io = new FakeIo(); io.waitDelay = 150;
        try(Fixture fixture = new Fixture(io,new AtomicBoolean(true),100)) {
            fixture.connect(); ChannelFuture sent = fixture.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{7}));
            assertTrue(sent.await(2,TimeUnit.SECONDS)); assertFalse(sent.isSuccess()); assertEquals(0,io.writes.get());
        }
    }
    @Test public void abortShutsDownBlockedPollAndOwnerClosesOnlyAfterIoReturns() throws Exception {
        FakeIo io = new FakeIo(); io.blockWait = true;
        try(Fixture fixture = new Fixture(io,new AtomicBoolean(true),2000)) {
            fixture.connect(); ChannelFuture sent = fixture.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{7}));
            assertTrue(io.waitEntered.await(1,TimeUnit.SECONDS)); fixture.channel.abort();
            assertTrue(sent.await(2,TimeUnit.SECONDS)); assertFalse(sent.isSuccess());
            assertTrue(fixture.channel.closeFuture().await(2,TimeUnit.SECONDS));
            assertEquals(0,io.writes.get()); assertTrue(io.shutdowns.get()>0); assertTrue(io.closed);
            assertFalse(io.closedDuringIo); assertEquals(io.owner,io.closeOwner);
        }
    }
    @Test public void smallProgressCannotExtendAbsoluteDeadline() throws Exception {
        FakeIo io = new FakeIo(); io.maximumWrite=1; io.waitDelay=10;
        try(Fixture fixture = new Fixture(io,new AtomicBoolean(true),100)) {
            fixture.connect(); ChannelFuture sent=fixture.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[1000]));
            assertTrue(sent.await(2,TimeUnit.SECONDS)); assertFalse(sent.isSuccess());
            assertTrue(io.writes.get()<1000);
        }
    }
    @Test public void failedIdentityReadClosesWithoutProtocolBytes() throws Exception {
        FakeIo io = new FakeIo(); io.failPeer=true;
        try(Fixture fixture=new Fixture(io,new AtomicBoolean(true),2000)) {
            assertThrows(ExecutionException.class,fixture::connect); assertTrue(io.closed); assertEquals(0,io.writes.get());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final OioEventLoopGroup group=new TorSocketEventLoopGroup();
        final TorSocketChannel channel;
        Fixture(FakeIo io, AtomicBoolean ready,int deadlineMillis)throws Exception {
            channel=new TorSocketChannel(ENDPOINT,ready::get,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(deadlineMillis),endpoint->io);
            channel.config().setAutoRead(false); group.register(channel).sync();
        }
        void connect() throws Exception {
            channel.eventLoop().submit(()->{channel.doConnect(ENDPOINT,null);return null;}).get(2,TimeUnit.SECONDS);
        }
        public void close() { TorSocketChannel.shutdown(channel,group); }
    }
    private static final class FakeIo implements TorSocketIo {
        int uid=ENDPOINT.uid,pid=ENDPOINT.pid,maximumWrite=Integer.MAX_VALUE;
        long waitDelay;
        volatile boolean closed,closedDuringIo,busy,blockWait,identityRead,failPeer;
        volatile Thread owner,closeOwner;
        Runnable afterWait=()->{};
        final AtomicInteger writes=new AtomicInteger(),shutdowns=new AtomicInteger();
        final CountDownLatch waitEntered=new CountDownLatch(1),release=new CountDownLatch(1);
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        public void connect(int timeout) { owner=Thread.currentThread();assertTrue(timeout>0&&timeout<=1000); }
        public int peerUid()throws IOException { if(failPeer)throw new IOException("fixture");return uid; }
        public int peerPid() { identityRead=true;return pid; }
        public int read(byte[] bytes,int length,int wait) { return -1; }
        public boolean awaitWritable(int wait)throws IOException {
            busy=true;waitEntered.countDown();
            try {
                if(blockWait&&!release.await(2,TimeUnit.SECONDS))throw new IOException("fixture wait");
                if(waitDelay>0)Thread.sleep(waitDelay);afterWait.run();return true;
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("fixture interrupted");}
            finally{busy=false;}
        }
        public int write(byte[] bytes,int length) { assertTrue(identityRead);writes.incrementAndGet();int count=Math.min(length,maximumWrite);output.write(bytes,0,count);return count; }
        public void shutdown() { shutdowns.incrementAndGet();release.countDown(); }
        public void close() { closedDuringIo|=busy;closed=true;closeOwner=Thread.currentThread(); }
    }
}
