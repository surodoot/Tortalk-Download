package dev.gyeopdam.embeddedtor;
import org.junit.Test;
import static org.junit.Assert.*;
public final class TorTalkStateTest {
    @Test public void opensWelcomeNotDiagnostics() { assertEquals(TorTalkState.Page.WELCOME, new TorTalkState().page); }
    @Test public void accountPagesRequireAuthentication() {
        TorTalkState s = new TorTalkState();
        for (TorTalkState.Page p : new TorTalkState.Page[]{TorTalkState.Page.CHAT, TorTalkState.Page.CONVERSATIONS, TorTalkState.Page.INVITATIONS}) {
            s.navigate(p); assertEquals(TorTalkState.Page.WELCOME, s.page);
        }
    }
    @Test public void repeatedTapIsRejected() { TorTalkState s = new TorTalkState(); assertTrue(s.begin() > 0); assertEquals(-1, s.begin()); }
    @Test public void backInvalidatesLateLogin() { TorTalkState s = new TorTalkState(); s.navigate(TorTalkState.Page.LOGIN); long id = s.begin(); assertTrue(s.back()); assertFalse(s.complete(id)); }
    @Test public void newerRequestOwnsCompletion() { TorTalkState s = new TorTalkState(); long old = s.begin(); s.cancel(); long now = s.begin(); assertFalse(s.complete(old)); assertTrue(s.complete(now)); }
    @Test public void signupShowsRecoveryBeforeConversations() { TorTalkState s = new TorTalkState(); s.signedIn(true); assertEquals(TorTalkState.Page.RECOVERY, s.page); assertFalse(s.back()); }
    @Test public void loginShowsConversations() { TorTalkState s = new TorTalkState(); s.signedIn(false); assertEquals(TorTalkState.Page.CONVERSATIONS, s.page); }
    @Test public void settingsBackReturnsToPrivacy() { TorTalkState s = new TorTalkState(); s.navigate(TorTalkState.Page.SETTINGS); assertTrue(s.back()); assertEquals(TorTalkState.Page.PRIVACY, s.page); }
    @Test public void logoutInvalidatesRequestsAndRoom() { TorTalkState s = new TorTalkState(); s.signedIn(false); s.selectedRoom="synthetic"; long id=s.begin(); s.logout(); assertFalse(s.complete(id)); assertNull(s.selectedRoom); assertFalse(s.authenticated); }
    @Test public void chatBackReturnsToList() { TorTalkState s = new TorTalkState(); s.signedIn(false); s.navigate(TorTalkState.Page.CHAT); assertTrue(s.back()); assertEquals(TorTalkState.Page.CONVERSATIONS,s.page); }
}
