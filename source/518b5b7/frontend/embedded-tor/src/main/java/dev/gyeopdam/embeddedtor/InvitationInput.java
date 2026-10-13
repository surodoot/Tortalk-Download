package dev.gyeopdam.embeddedtor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Native-only invite parsing; clipboard imports may carry an origin binding. */
final class InvitationInput {
    final String id, token;
    private InvitationInput(String id,String token){this.id=id;this.token=token;}
    static InvitationInput parse(String first,String second,String expectedOrigin){
        if(first==null||second==null||expectedOrigin==null||first.length()>1024||second.length()>256)throw invalid();
        String value=first.trim(),token=second.trim();String[] parts=value.split("\\s+");
        if(parts.length==4){
            if(!"tortalk-invite-v1".equals(parts[0])||!expectedOrigin.equals(parts[1])||!token.isEmpty())throw invalid();
            value=parts[2];token=parts[3];
        }else if(parts.length!=1)throw invalid();
        if(!UiCommandPolicy.validOpaqueId(value)||!token.matches("gd_inv_[A-Za-z0-9_-]{43}"))throw invalid();
        return new InvitationInput(value,token);
    }
    static String copyValue(String origin,String id,String token){return "tortalk-invite-v1\n"+origin+"\n"+id+"\n"+token;}
    static String consumeKey(String origin,String account,String invitation){
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest((origin+"\n"+account+"\n"+invitation).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder("demo-accept-");for(byte b:hash){out.append("0123456789abcdef".charAt((b&255)>>>4));out.append("0123456789abcdef".charAt(b&15));}return out.toString();}catch(Exception impossible){throw new IllegalStateException("HASH_UNAVAILABLE");}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_INVITATION");}
}
