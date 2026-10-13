package dev.gyeopdam.embeddedtor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Immutable, bounded request for one explicitly configured onion origin. Never logs credentials. */
final class OnionApiRequest {
    static final int MAX_REQUEST_BYTES = 64 * 1024;
    static final int MAX_RESPONSE_BYTES = 96 * 1024;
    private static final Pattern ORIGIN = Pattern.compile("http://[a-z2-7]{56}\\.onion(?::80)?/?");
    private static final Pattern PATH = Pattern.compile("/v1/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*");
    final String host;
    final String origin;
    final String method;
    final String path;
    private final String sessionToken;
    private final String deviceToken;
    private final byte[] body;

    static String canonicalOrigin(String value) {
        if (value == null || !ORIGIN.matcher(value).matches()) throw new IllegalArgumentException("INVALID_ONION_ORIGIN");
        String host = value.substring(7).replaceFirst(":80/?$", "").replaceFirst("/$", "");
        return "http://" + host;
    }
    OnionApiRequest(String origin, String method, String path, byte[] body,
                    String sessionToken, String deviceToken, String boundOrigin) {
        this.origin = canonicalOrigin(origin);
        this.host = this.origin.substring(7);
        if (!("GET".equals(method) || "POST".equals(method) || "DELETE".equals(method))) {
            throw new IllegalArgumentException("INVALID_METHOD");
        }
        if (path == null || path.length() > 512 || !PATH.matcher(path).matches()) throw new IllegalArgumentException("INVALID_PATH");
        if (body == null || body.length > MAX_REQUEST_BYTES || (!"POST".equals(method) && body.length != 0)) {
            throw new IllegalArgumentException("INVALID_BODY");
        }
        this.sessionToken = token(sessionToken);
        this.deviceToken = token(deviceToken);
        if ((!this.sessionToken.isEmpty() || !this.deviceToken.isEmpty())
                && !this.origin.equals(canonicalOrigin(boundOrigin))) {
            throw new IllegalArgumentException("CREDENTIAL_ORIGIN_MISMATCH");
        }
        if (!this.deviceToken.isEmpty() && this.sessionToken.isEmpty()) throw new IllegalArgumentException("SESSION_REQUIRED");
        this.method = method; this.path = path; this.body = body.clone();
    }
    private static String token(String token) {
        if (token == null) return "";
        if (token.length() > 512) throw new IllegalArgumentException("INVALID_TOKEN");
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 33 || c > 126) throw new IllegalArgumentException("INVALID_TOKEN");
        }
        return token;
    }
    byte[] body() { return body.clone(); }
    String sessionToken() { return sessionToken; }
    String deviceToken() { return deviceToken; }
    void destroyBody() { Arrays.fill(body, (byte) 0); }
    @Override public String toString() { return "OnionApiRequest([redacted])"; }
}
