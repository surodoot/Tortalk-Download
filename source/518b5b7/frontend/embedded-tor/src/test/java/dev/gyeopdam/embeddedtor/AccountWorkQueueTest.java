package dev.gyeopdam.embeddedtor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountWorkQueueTest {
    @Test public void newOwnerCannotLoadAheadOfQueuedLogout()throws Exception {
        Executor oldOwner=AccountWorkQueue.INSTANCE,newOwner=AccountWorkQueue.INSTANCE;
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicReference<String> disk=new AtomicReference<>("synthetic remembered session"),loaded=new AtomicReference<>("not loaded");
        oldOwner.execute(()->{held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(held.await(5,TimeUnit.SECONDS));
        try {
            oldOwner.execute(()->disk.set(null));
            newOwner.execute(()->{loaded.set(disk.get());done.countDown();});
            assertEquals(1,done.getCount());
        }finally{release.countDown();}
        assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(loaded.get());
    }
    @Test public void failedDeletionCannotRestoreInNewOwner()throws Exception {
        long cleanup=AccountWorkQueue.beginCleanup();
        try {assertFalse(AccountWorkQueue.restorationAllowed());
            AtomicReference<String> bound=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
            AccountWorkQueue.INSTANCE.execute(()->{if(AccountWorkQueue.restorationAllowed())bound.set("old readable record");done.countDown();});
            assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(bound.get());
        }finally{AccountWorkQueue.completeCleanup(cleanup);}
        assertTrue(AccountWorkQueue.restorationAllowed());
    }
    @Test public void staleCleanupCannotClearNewFailureFence(){
        long first=AccountWorkQueue.beginCleanup(),second=AccountWorkQueue.beginCleanup();
        AccountWorkQueue.completeCleanup(first);assertFalse(AccountWorkQueue.restorationAllowed());
        AccountWorkQueue.completeCleanup(second);assertTrue(AccountWorkQueue.restorationAllowed());
    }
    @Test public void priorCapturedSaveFinishesBeforeClear()throws Exception {
        AtomicReference<String> disk=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        AccountWorkQueue.INSTANCE.execute(()->disk.set("synthetic captured save"));
        AccountWorkQueue.INSTANCE.execute(()->disk.set(null));
        AccountWorkQueue.INSTANCE.execute(done::countDown);
        assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(disk.get());
    }
}
