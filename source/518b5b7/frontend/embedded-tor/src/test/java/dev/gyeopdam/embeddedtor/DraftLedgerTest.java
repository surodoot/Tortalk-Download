package dev.gyeopdam.embeddedtor;
import org.junit.Test;
import static org.junit.Assert.*;
public final class DraftLedgerTest {
 @Test public void repeatedSameRevisionReusesMessageId(){DraftLedger l=new DraftLedger();DraftLedger.Entry a=l.bind("1","demo-room-a","synthetic");assertSame(a,l.bind("1","demo-room-a","synthetic"));}
 @Test(expected=IllegalArgumentException.class)public void changedTextSameRevisionRejected(){DraftLedger l=new DraftLedger();l.bind("1","demo-room-a","a");l.bind("1","demo-room-a","b");}
 @Test(expected=IllegalArgumentException.class)public void changedRoomSameRevisionRejected(){DraftLedger l=new DraftLedger();l.bind("1","demo-room-a","a");l.bind("1","demo-room-b","a");}
 @Test public void newRevisionAllowsIntentionalRepeatedText(){DraftLedger l=new DraftLedger();assertNotEquals(l.bind("1","demo-room-a","same").messageId,l.bind("2","demo-room-a","same").messageId);}
 @Test public void oldDocumentCannotClearNewIdenticalDraft(){DraftLedger l=new DraftLedger();DraftLedger.Entry old=l.bind("1","demo-room-a","same");l.reset();DraftLedger.Entry now=l.bind("1","demo-room-a","same");assertFalse(l.current(old));assertTrue(l.current(now));}
 @Test(expected=IllegalArgumentException.class)public void evictedRevisionCannotMintNewMessage(){DraftLedger l=new DraftLedger();for(int i=1;i<=65;i++)l.bind(String.valueOf(i),"demo-room-a","a");l.bind("1","demo-room-a","a");}
 @Test(expected=IllegalArgumentException.class)public void rejectsZeroRevision(){new DraftLedger().bind("0","demo-room-a","a");}
 @Test(expected=IllegalArgumentException.class)public void boundsUtf8NotOnlyCharacters(){new DraftLedger().bind("1","demo-room-a","가".repeat(2000));}
 @Test public void unchangedDraftSurvivesCancellationOutsideLedger(){DraftLedger l=new DraftLedger();DraftLedger.Entry old=l.bind("1","demo-room-a","same");assertTrue(l.current(old));assertEquals(old.messageId,l.bind("1","demo-room-a","same").messageId);}
}
