package dev.gyeopdam.embeddedtor;
import org.junit.Test;
import static org.junit.Assert.*;
public final class CoordinatorFailuresTest {
 @Test public void explicitExpiredSessionRequestsLogin(){assertTrue(CoordinatorFailures.expiresSession("AUTH_REQUIRED"));assertTrue(CoordinatorFailures.expiresSession("LOGIN_REQUIRED"));}
 @Test public void DurableQueuedAuthFailureStillRequestsLogin(){assertTrue(CoordinatorFailures.expiresSession("QUEUED_AUTH_REQUIRED"));assertTrue(CoordinatorFailures.expiresSession("QUEUED_LOGIN_REQUIRED"));}
 @Test public void cancellationAndCryptoDoNotLogOut(){for(String code:new String[]{"CANCELLED","CRYPTO_OR_STORAGE_FAILED","QUEUED_TIMEOUT","PEER_NOT_VERIFIED","CONFLICT_OR_PREKEYS_EXHAUSTED",""})assertFalse(CoordinatorFailures.expiresSession(code));assertFalse(CoordinatorFailures.expiresSession(null));}
 @Test public void arbitraryPrefixSuffixNeverCountsAsAuth(){assertFalse(CoordinatorFailures.expiresSession("UNTRUSTED_AUTH_REQUIRED"));assertFalse(CoordinatorFailures.expiresSession("AUTH_REQUIRED_extra"));}
 @Test public void authTransitionClearsOnlyVolatileUiState(){TorTalkState state=new TorTalkState();state.signedIn(false);long request=state.begin();assertTrue(state.complete(request));if(CoordinatorFailures.expiresSession("QUEUED_AUTH_REQUIRED"))state.logout();assertFalse(state.authenticated);assertEquals(TorTalkState.Page.WELCOME,state.page);}
}
