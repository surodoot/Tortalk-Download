package dev.gyeopdam.embeddedtor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Local storage domain identifier only. Never emitted to logs, network or the WebView. */
final class SignalProfile {
    static String namespace(String origin,String account) {
        if(!OnionApiRequest.canonicalOrigin(origin).equals(origin)||account==null||!account.matches("[a-z0-9][a-z0-9-]{5,79}"))throw new IllegalArgumentException("INVALID_PROFILE");
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((origin+"\u0000"+account).getBytes(StandardCharsets.US_ASCII));
            char[] chars=new char[64];final char[] hex="0123456789abcdef".toCharArray();
            for(int i=0;i<digest.length;i++){chars[i*2]=hex[(digest[i]&255)>>>4];chars[i*2+1]=hex[digest[i]&15];}
            return "signal-"+new String(chars);
        }catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA256_UNAVAILABLE");}
    }
}
