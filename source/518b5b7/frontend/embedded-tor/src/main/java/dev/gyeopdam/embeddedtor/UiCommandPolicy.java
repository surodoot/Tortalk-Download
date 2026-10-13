package dev.gyeopdam.embeddedtor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
/** Narrow command surface. No URL, endpoint, raw API method, key export or script command. */
final class UiCommandPolicy {
    static final int MAX_COMMAND_CHARS=8192;
    private static final Set<String> ACTIONS=new HashSet<>(Arrays.asList("ready","route","begin","login","setup","refresh","create_invite","accept_invite","contact","developer","logout","open_room","send_message","verify_peer","retry_pending","devices","history_older","history_newer"));
    static boolean allows(String action) { return action!=null && ACTIONS.contains(action); }
    static boolean validRoute(String route) { return Arrays.asList("welcome","chats","invite","privacy","settings","chat").contains(route); }
    static boolean validDraftId(String value) { return value!=null && value.matches("[1-9][0-9]{0,8}"); }
    static boolean validOpaqueId(String value) { return value!=null && value.matches("[a-z0-9][a-z0-9-]{5,79}"); }
}
