package dev.gyeopdam.embeddedtor;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
public class ServerConfigStoreTest {
 private static final String ORIGIN="http://"+repeat('a',56)+".onion";
 private static final String TOKEN="tt_join_"+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
 @Test public void exactBoundOriginAndAdmissionRoundTrip()throws Exception{
  ServerConfigStore.Config value=ServerConfigStore.decode(ServerConfigStore.encode(new ServerConfigStore.Config(ORIGIN,TOKEN)));
  assertEquals(ORIGIN,value.serverOnion);assertEquals(TOKEN,value.registrationToken);
 }
 @Test public void emptyAdmissionSupportsLoginOnly()throws Exception{assertEquals("",ServerConfigStore.decode(ServerConfigStore.encode(new ServerConfigStore.Config(ORIGIN,""))).registrationToken);}
 @Test public void nonCanonicalAndWrongTokenRejected(){
  for(String token:new String[]{"secret",TOKEN+"=",TOKEN+"\n","gd_acct_"+TOKEN.substring(8),"tt_join_"+repeat('a',42)})
   assertThrows(IOException.class,()->ServerConfigStore.encode(new ServerConfigStore.Config(ORIGIN,token)));
 }
 @Test public void corruptVersionAndTrailingBytesRejected()throws Exception{
  byte[] bytes=ServerConfigStore.encode(new ServerConfigStore.Config(ORIGIN,TOKEN));
  byte[] changed=bytes.clone();changed[4]=9;assertThrows(IOException.class,()->ServerConfigStore.decode(changed));
  assertThrows(IOException.class,()->ServerConfigStore.decode(Arrays.copyOf(bytes,bytes.length+1)));
 }
 @Test public void everyTruncationRejected()throws Exception{
  byte[] bytes=ServerConfigStore.encode(new ServerConfigStore.Config(ORIGIN,TOKEN));
  for(int n=0;n<bytes.length;n++){byte[] truncated=Arrays.copyOf(bytes,n);assertThrows(IOException.class,()->ServerConfigStore.decode(truncated));}
 }
 @Test public void configToStringNeverContainsSecret(){assertFalse(new ServerConfigStore.Config(ORIGIN,TOKEN).toString().contains(TOKEN));assertFalse(new ServerConfigStore.Config(ORIGIN,TOKEN).toString().contains(ORIGIN));}
 private static String repeat(char c,int n){char[] value=new char[n];Arrays.fill(value,c);return new String(value);}
}
