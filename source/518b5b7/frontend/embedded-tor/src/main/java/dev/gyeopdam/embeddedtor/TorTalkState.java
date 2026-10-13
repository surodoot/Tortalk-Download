package dev.gyeopdam.embeddedtor;

/** Pure navigation and request ownership. Secrets and message bodies never enter saved view state. */
final class TorTalkState {
    enum Page { WELCOME, SIGNUP, LOGIN, RECOVERY, CONVERSATIONS, INVITATIONS, PRIVACY, SETTINGS, CHAT }
    Page page = Page.WELCOME;
    boolean authenticated;
    boolean busy;
    private long generation;
    String error = "";
    String selectedRoom;

    long begin() {
        if (busy) return -1;
        busy = true; error = ""; return ++generation;
    }
    boolean owns(long request) { return busy && request == generation; }
    boolean complete(long request) {
        if (!busy || request != generation) return false;
        busy = false; return true;
    }
    void navigate(Page next) {
        if (!authenticated && requiresAccount(next)) return;
        cancel(); page = next; error = "";
        if (next != Page.CHAT) selectedRoom = null;
    }
    void cancel() { generation++; busy = false; }
    void signedIn(boolean newlyCreated) {
        authenticated = true;
        navigate(newlyCreated ? Page.RECOVERY : Page.CONVERSATIONS);
    }
    void logout() {
        cancel(); authenticated = false; selectedRoom = null; error = ""; page = Page.WELCOME;
    }
    boolean back() {
        if (page == Page.WELCOME || page == Page.CONVERSATIONS || page == Page.RECOVERY) return false;
        if (page == Page.SETTINGS) navigate(Page.PRIVACY);
        else navigate(authenticated ? Page.CONVERSATIONS : Page.WELCOME);
        return true;
    }
    private static boolean requiresAccount(Page page) {
        return page == Page.CONVERSATIONS || page == Page.INVITATIONS || page == Page.CHAT || page == Page.RECOVERY;
    }
}
