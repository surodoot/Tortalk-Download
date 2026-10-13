package dev.gyeopdam.embeddedtor;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure policy tests; Android Activity/service scheduling remains a device integration concern. */
public class TorLifecyclePolicyTest {
    @Test public void startupNeedsForegroundAndExplicitOptIn() {
        TorLifecyclePolicy policy = new TorLifecyclePolicy();
        assertFalse(policy.requested()); assertFalse(policy.canStart()); assertFalse(policy.start());
        assertEquals(0,policy.generation); assertEquals(0,policy.foregroundCount());
        policy.foreground(); assertTrue(policy.canStart()); assertFalse(policy.requested());
        assertTrue(policy.start()); assertTrue(policy.requested()); assertFalse(policy.canStart());
        assertEquals(1,policy.generation);
    }
    @Test public void repeatedStartDoesNotCreateSecondGeneration() {
        TorLifecyclePolicy policy = running(); long epoch=policy.generation;
        for(int i=0;i<20;i++) assertFalse(policy.start());
        assertEquals(epoch,policy.generation); assertTrue(policy.requested());
    }
    @Test public void overlappingActivityNavigationKeepsSingleOptInUntilLastOwnerLeaves() {
        TorLifecyclePolicy policy = running(); long epoch=policy.generation;
        policy.foreground(); assertEquals(2,policy.foregroundCount());
        assertFalse(policy.start()); assertFalse(policy.background());
        assertEquals(1,policy.foregroundCount()); assertTrue(policy.requested()); assertEquals(epoch,policy.generation);
        assertTrue(policy.background()); assertFalse(policy.requested()); assertEquals(epoch+1,policy.generation);
        assertFalse(policy.canStart()); assertEquals(0,policy.foregroundCount());
    }
    @Test public void backgroundClearsConsentAndForegroundRequiresAnotherExplicitStart() {
        TorLifecyclePolicy policy = running(); assertTrue(policy.background());
        assertFalse(policy.requested()); assertFalse(policy.start()); long epoch=policy.generation;
        policy.foreground(); assertFalse(policy.requested()); assertTrue(policy.canStart()); assertEquals(epoch,policy.generation);
        assertTrue(policy.start()); assertEquals(epoch+1,policy.generation); assertTrue(policy.requested());
    }
    @Test public void unmatchedBackgroundNeverUnderflowsOrManufacturesConsent() {
        TorLifecyclePolicy policy = new TorLifecyclePolicy();
        for(int i=0;i<20;i++) assertFalse(policy.background());
        assertEquals(0,policy.foregroundCount()); assertEquals(0,policy.generation);
        policy.foreground(); assertFalse(policy.background()); assertEquals(0,policy.foregroundCount());
        assertFalse(policy.requested()); assertFalse(policy.canStart());
    }
    @Test public void explicitStopInvalidatesOldGenerationAndAllowsExplicitForegroundRestart() {
        TorLifecyclePolicy policy = running(); long epoch=policy.generation;
        policy.stop(); assertFalse(policy.requested()); assertEquals(epoch+1,policy.generation); assertTrue(policy.canStart());
        assertTrue(policy.start()); assertTrue(policy.requested()); assertEquals(epoch+2,policy.generation);
        policy.stop(); policy.background(); assertFalse(policy.start());
    }
    @Test public void stoppingOneScreenRevokesSharedOptInEvenWithOtherOwners() {
        TorLifecyclePolicy policy = running(); policy.foreground(); policy.stop();
        assertEquals(2,policy.foregroundCount()); assertFalse(policy.requested()); assertTrue(policy.canStart());
        assertFalse(policy.background()); assertFalse(policy.requested());
    }
    @Test public void fatalFailureCannotBeReenabledByStartStopOrNavigation() {
        TorLifecyclePolicy policy = running(); long epoch=policy.generation;
        policy.fail(); assertTrue(policy.failed()); assertFalse(policy.requested()); assertFalse(policy.canStart());
        assertEquals(epoch+1,policy.generation); assertFalse(policy.start());
        policy.stop(); assertFalse(policy.start()); policy.background(); policy.foreground();
        assertFalse(policy.start()); assertFalse(policy.canStart()); assertFalse(policy.requested());
    }
    @Test public void newProcessPolicyNeverRestoresReadinessOrConsent() {
        TorLifecyclePolicy old = running(); old.fail();
        TorLifecyclePolicy fresh = new TorLifecyclePolicy(); fresh.foreground();
        assertFalse(fresh.failed()); assertFalse(fresh.requested()); assertTrue(fresh.canStart()); assertEquals(0,fresh.generation);
    }
    private static TorLifecyclePolicy running() { TorLifecyclePolicy policy=new TorLifecyclePolicy(); policy.foreground(); assertTrue(policy.start()); return policy; }
}
