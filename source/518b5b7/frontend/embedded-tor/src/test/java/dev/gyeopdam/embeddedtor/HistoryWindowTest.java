package dev.gyeopdam.embeddedtor;
import java.util.HashSet;
import java.util.Set;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class HistoryWindowTest {
 private JSONObject message(int n,String text)throws Exception{return new JSONObject().put("id","demo-message-"+n).put("text",text).put("own",true).put("status","pending").put("device_token","never-expose");}
 @Test public void ordinaryPageKeepsLastForty()throws Exception {JSONArray all=new JSONArray();for(int i=0;i<70;i++)all.put(message(i,"synthetic"));HistoryWindow page=HistoryWindow.of(all,-1);assertEquals(30,page.start);assertEquals(70,page.end);assertEquals(40,page.items.length());assertFalse(page.items.toString().contains("never-expose"));}
 @Test public void escapedQuotesRespectActualBudget()throws Exception {JSONArray all=new JSONArray();for(int i=0;i<40;i++)all.put(message(i,"\"".repeat(4096)));HistoryWindow page=HistoryWindow.of(all,-1);assertTrue(page.items.length()<40);assertTrue(page.items.toString().length()<=HistoryWindow.MAX_JSON_CHARACTERS);}
 @Test public void controlCharactersRespectActualBudget()throws Exception {JSONArray all=new JSONArray();for(int i=0;i<40;i++)all.put(message(i,"\u0001".repeat(4096)));HistoryWindow page=HistoryWindow.of(all,-1);assertTrue(page.items.length()<40);assertTrue(page.items.toString().length()<=HistoryWindow.MAX_JSON_CHARACTERS);}
 @Test public void budgetedPagesNeverSkipOrDuplicate()throws Exception {JSONArray all=new JSONArray();for(int i=0;i<91;i++)all.put(message(i,i%2==0?"\u0001".repeat(4096):"synthetic"));Set<String> seen=new HashSet<>();int end=-1;while(true){HistoryWindow page=HistoryWindow.of(all,end);for(int i=0;i<page.items.length();i++)assertTrue(seen.add(page.items.getJSONObject(i).getString("id")));if(page.start==0)break;end=page.start;}assertEquals(91,seen.size());}
 @Test public void emptyHistoryHasNoPhantomMessages(){HistoryWindow page=HistoryWindow.of(new JSONArray(),-1);assertEquals(0,page.items.length());assertEquals(0,page.start);}
 @Test(expected=IllegalArgumentException.class)public void unknownStatusRejected()throws Exception {HistoryWindow.of(new JSONArray().put(message(1,"synthetic").put("status","read-by-human")),-1);}
 @Test(expected=IllegalArgumentException.class)public void nonStringTextRejected()throws Exception {HistoryWindow.of(new JSONArray().put(message(1,"synthetic").put("text",new JSONObject())),-1);}
}
