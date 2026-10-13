package dev.gyeopdam.embeddedtor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Process-owned FIFO: a recreated Activity cannot restore ahead of an accepted local logout. */
final class AccountWorkQueue {
    private static final Executor WORKER=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"tortalk-account-store");thread.setDaemon(true);return thread;
    });
    static final Executor INSTANCE=task->WORKER.execute(task);
    private static long cleanupGeneration;
    private static boolean restoreForbidden;
    static synchronized long beginCleanup(){restoreForbidden=true;return ++cleanupGeneration;}
    static synchronized void completeCleanup(long generation){if(generation==cleanupGeneration)restoreForbidden=false;}
    static synchronized boolean restorationAllowed(){return !restoreForbidden;}
    private AccountWorkQueue() { }
}
