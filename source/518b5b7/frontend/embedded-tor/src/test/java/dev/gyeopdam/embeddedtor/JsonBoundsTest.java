package dev.gyeopdam.embeddedtor;

import org.junit.Test;
import static org.junit.Assert.*;

/** Strict grammar/resource guard; the API facade separately requires an object root. */
public class JsonBoundsTest {
    @Test public void allowsOrdinaryNestedJson() {
        assertTrue(JsonBounds.safe("{\"items\":[{\"value\":1},null,true],\"empty\":{}}"));
        assertTrue(JsonBounds.safe(" \r\n{\"body\":\"한글\"}\t"));
    }
    @Test public void rejectsDuplicateKeysAtEveryObjectDepth() {
        for(String text:new String[]{"{\"code\":\"OTHER\",\"code\":\"UNAUTHORIZED\"}",
                "{\"code\":\"UNAUTHORIZED\",\"code\":\"OTHER\"}","{\"same\":1,\"same\":1}","{\"\":0,\"\":1}",
                "{\"result\":{\"status\":\"pending\",\"status\":\"recovered\"}}",
                "{\"items\":[{},[{\"device_id\":\"first\",\"device_id\":\"second\"}]]}",
                repeat('[',31)+"{\"x\":1,\"x\":2}"+repeat(']',31)})
            assertFalse(text,JsonBounds.safe(text));
    }
    @Test public void comparesDecodedKeysIncludingEveryStandardEscapeAndUnicode() {
        String[][] sameKeys={{"code","c\\u006fde"},{"\\u0063ode","code"},{"\\u0041","A"},{"\\uAbCd","\\uabcd"},
                {"/","\\/"},{"\\/","\\u002f"},{"\\\"","\\u0022"},{"\\\\","\\u005c"},
                {"\\b","\\u0008"},{"\\f","\\u000c"},{"\\n","\\u000a"},{"\\r","\\u000d"},{"\\t","\\u0009"},
                {"한글","\\ud55c\\uae00"},{"é","\\u00e9"},{"😀","\\ud83d\\ude00"},{"\\u0000","\\u0000"}};
        for(String[] keys:sameKeys) {
            String text="{\""+keys[0]+"\":0,\""+keys[1]+"\":1}";
            assertFalse(text,JsonBounds.safe(text));
            assertFalse(text,JsonBounds.safe("{\"items\":["+text+"]}"));
        }
    }
    @Test public void identicalKeysInSeparateObjectsAndDistinctDecodedNamesRemainAllowed() {
        assertTrue(JsonBounds.safe("{\"code\":\"outer\",\"child\":{\"code\":\"inner\"},\"items\":[{\"code\":1},{\"c\\u006fde\":2}]}"));
        assertTrue(JsonBounds.safe("{\"code\":0,\"Code\":1,\"\\\\u0063ode\":2,\"é\":3,\"e\\u0301\":4,\"\":5,\"\\u0000\":6}"));
        String key="\\\"[,]{}:"+"\\\\";
        assertTrue(JsonBounds.safe("{\""+key+"\":{\""+key+"\":true}}"));
    }
    @Test public void malformedKeyEscapesAndControlsStillFail() {
        for(String key:new String[]{"\\x","\\'","\\v","\\0","\\u","\\u123","\\u123x","\\u１２３４","\\\n","\\\t"})
            assertFalse(key,JsonBounds.safe("{\""+key+"\":0}"));
        for(int c=0;c<32;c++)assertFalse("control "+c,JsonBounds.safe("{\"a"+(char)c+"b\":0}"));
        assertFalse(JsonBounds.safe("{\"unfinished"));
        assertFalse(JsonBounds.safe("{\"trailing\\"));
    }
    @Test public void decodedKeyAllocationPreservesExistingLengthBoundary() {
        assertTrue(JsonBounds.safe("{\""+repeat('x',OnionApiRequest.MAX_RESPONSE_BYTES-6)+"\":0}"));
        assertFalse(JsonBounds.safe("{\""+repeat('x',OnionApiRequest.MAX_RESPONSE_BYTES-5)+"\":0}"));
    }
    @Test public void depth32IsAllowedAnd33IsRejected() {
        assertTrue(JsonBounds.safe(repeat('[',32) + "0" + repeat(']',32)));
        assertFalse(JsonBounds.safe(repeat('[',33) + "0" + repeat(']',33)));
        assertTrue(JsonBounds.safe(nestedObjects(32)));
        assertFalse(JsonBounds.safe(nestedObjects(33)));
    }
    @Test public void lengthIsBoundedBeforeParsing() {
        assertTrue(JsonBounds.safe("\"" + repeat('x',OnionApiRequest.MAX_RESPONSE_BYTES-2) + "\""));
        assertFalse(JsonBounds.safe("\"" + repeat('x',OnionApiRequest.MAX_RESPONSE_BYTES-1) + "\""));
        assertFalse(JsonBounds.safe(null));
    }
    @Test public void bracesAndEscapedQuotesInsideStringsDoNotAffectDepth() {
        assertTrue(JsonBounds.safe("{\"text\":\"" + repeat('[',100) + repeat('}',100) + "\"}"));
        assertTrue(JsonBounds.safe("{\"text\":\"escaped \\\" [ { \\\" and \\\\ slash\"}"));
        assertTrue(JsonBounds.safe("{\"text\":\"\\u005b\\n\\t\"}"));
    }
    @Test public void unterminatedQuotesTrailingEscapeAndUnbalancedDepthFail() {
        for(String text:new String[]{"{","[","}","]","{}]","{\"x\":[]","\"unfinished","\"trailing\\","{\"x\":\"unterminated}"})
            assertFalse(text,JsonBounds.safe(text));
    }
    @Test public void literalControlsInsideStringsFail() {
        for(int c=0;c<32;c++) assertFalse("control " + c,JsonBounds.safe("{\"text\":\"a" + (char)c + "b\"}"));
    }
    @Test public void lenientParserCommentsCannotHideDeepNestingFromGuard() {
        String nested = repeat('[',64) + "0" + repeat(']',64);
        assertFalse(JsonBounds.safe("{\"a\": /*\"*/" + nested + "/*\"*/}"));
        assertFalse(JsonBounds.safe("{\"a\": //\"\n" + nested + "//\"\n}"));
        assertFalse(JsonBounds.safe("{\"a\": #\"\n" + nested + "#\"\n}"));
    }
    @Test public void lenientSingleQuoteAndUnquotedTokensCannotHideDeepNesting() {
        String nested = repeat('[',64) + "0" + repeat(']',64);
        assertFalse(JsonBounds.safe("{'a':'\"','b':" + nested + ",'c':'\"'}"));
        assertFalse(JsonBounds.safe("{a\":null,b:" + nested + ",c\":null}"));
    }
    @Test public void commentLikeTextAndApostrophesInsideJsonStringsRemainAllowed() {
        assertTrue(JsonBounds.safe("{\"url\":\"https://example.invalid/#anchor\",\"text\":\"don't /* parse */ this\"}"));
    }
    @Test public void strictGrammarRejectsEmptyInputAndTrailingDataButFacadeStillChecksObjectRoot() {
        assertFalse(JsonBounds.safe("")); assertFalse(JsonBounds.safe(" \t\r\n"));
        assertTrue(JsonBounds.safe("42")); assertTrue(JsonBounds.safe("null")); assertTrue(JsonBounds.safe("[]"));
        assertFalse(JsonBounds.safe("{} {}")); assertFalse(JsonBounds.safe("{}garbage"));
    }
    @Test public void validNumbersLiteralsAndWhitespaceRemainAllowed() {
        for(String value:new String[]{"0","-0","1","-1","1234567890","0.1","-0.5","1e2","1E-2","1e+2","12.34e-56","true","false","null"}) {
            assertTrue(value,JsonBounds.safe(value));
            assertTrue(value,JsonBounds.safe(" { \"value\" \t: \r\n" + value + " } "));
        }
    }
    @Test public void invalidNumbersAndLenientLiteralsFail() {
        for(String value:new String[]{"+1","01","-01",".1","1.","1e","1E+","1e-","--1","-","0x10","NaN","Infinity","TRUE","False","undefined","truefalse","1 2"})
            assertFalse(value,JsonBounds.safe("{\"value\":" + value + "}"));
    }
    @Test public void standardStringEscapesAreAcceptedAndUnknownOrTruncatedEscapesFail() {
        for(String escaped:new String[]{"\\\"","\\\\","\\/","\\b","\\f","\\n","\\r","\\t","\\u0000","\\uAbCd","\\ud83d\\ude00"})
            assertTrue(escaped,JsonBounds.safe("{\"x\":\"" + escaped + "\"}"));
        for(String escaped:new String[]{"\\x","\\'","\\v","\\0","\\u","\\u123","\\u123x","\\u１２３４","\\\n","\\\t"})
            assertFalse(escaped,JsonBounds.safe("{\"x\":\"" + escaped + "\"}"));
    }
    @Test public void malformedContainersAndNonJsonWhitespaceFail() {
        for(String value:new String[]{"{]","[}","{\"a\"}","{\"a\":}","{\"a\":1,}","[1,]","[,1]","[1,,2]","{\"a\"=1}","{\"a\":1;\"b\":2}","{'a':1}","{a:1}","\f{}","\u000b{}","\u00a0{}","\ufeff{}"})
            assertFalse(value,JsonBounds.safe(value));
    }
    @Test public void extremeInputIsRejectedBeforeGuardCanOverflowItsOwnStack() {
        assertFalse(JsonBounds.safe(repeat('[',40000) + "0" + repeat(']',40000)));
        assertFalse(JsonBounds.safe(repeat('[',98305)));
    }
    private static String nestedObjects(int count) {
        StringBuilder text=new StringBuilder(); for(int i=0;i<count;i++)text.append("{\"v\":");
        text.append('0'); for(int i=0;i<count;i++)text.append('}'); return text.toString();
    }
    private static String repeat(char c,int count) { char[] chars=new char[count]; java.util.Arrays.fill(chars,c); return new String(chars); }
}
