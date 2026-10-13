package dev.gyeopdam.embeddedtor;

import io.netty.channel.EventLoop;
import io.netty.channel.ThreadPerChannelEventLoop;
import io.netty.channel.ThreadPerChannelEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;

/** One retained owner; preserves Netty's loop while fixing its shutdown wakeup consumption race. */
@SuppressWarnings("deprecation")
final class TorSocketEventLoopGroup extends OioEventLoopGroup {
    TorSocketEventLoopGroup() { super(1); }
    @Override protected EventLoop newChild(Object... args) { return new Owner(this); }
    static class Owner extends ThreadPerChannelEventLoop {
        Owner(ThreadPerChannelEventLoopGroup parent) { super(parent); }
        @Override protected Runnable takeTask() {
            // Vendor runAllTasks() can consume the shutdown wakeup during deregistration.
            // Never block again after shutdown started; let run() reach confirmShutdown().
            return isShuttingDown() ? null : super.takeTask();
        }
    }
}
