package dev.gyeopdam.embeddedtor;
/** Only explicit authentication failures revoke the UI login; storage/crypto errors never do. */
final class CoordinatorFailures {
    static boolean expiresSession(String code) {
        return "AUTH_REQUIRED".equals(code)||"LOGIN_REQUIRED".equals(code)||"QUEUED_AUTH_REQUIRED".equals(code)||"QUEUED_LOGIN_REQUIRED".equals(code);
    }
}
