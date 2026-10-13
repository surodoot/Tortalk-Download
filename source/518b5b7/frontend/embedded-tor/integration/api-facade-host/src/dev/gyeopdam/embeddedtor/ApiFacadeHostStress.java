package dev.gyeopdam.embeddedtor;

import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/** Repeats the actual latch-controlled cancellation tests; no source replacement or relaxed assertions. */
public final class ApiFacadeHostStress {
    public static void main(String[] args) {
        int iterations=args.length==0?100:Integer.parseInt(args[0]);
        if(iterations<1||iterations>1000)throw new IllegalArgumentException("Iterations must be 1..1000");
        String[] methods={"originChangeRejectsOldCredentialsAndCancelsTwoRunningTwoQueuedCalls",
                "readinessLossCancelsQueuedAndRunningCallsWithoutNewConnections",
                "closeCancelsTwoRunningAndTwoQueuedCallsExactlyOnce",
                "closeWithThrowingCallbackStillCancelsAllCallsUnregistersAndStopsWorkers"};
        int runs=0,failures=0;long began=System.nanoTime();
        for(int i=0;i<iterations;i++)for(String method:methods){
            Result result=new JUnitCore().run(Request.method(ApiFacadeHostTest.class,method));
            runs+=result.getRunCount();failures+=result.getFailureCount();
            for(Failure failure:result.getFailures())System.err.println("Iteration "+(i+1)+", "+method+"\n"+failure.getTrace());
            if(!result.wasSuccessful()){System.err.println("Stress failed after "+runs+" scenarios; failures="+failures);System.exit(1);}
        }
        System.out.printf("Facade cancellation stress: %d scenarios, %d failures, %.3f seconds%n",runs,failures,(System.nanoTime()-began)/1_000_000_000.0);
    }
}
