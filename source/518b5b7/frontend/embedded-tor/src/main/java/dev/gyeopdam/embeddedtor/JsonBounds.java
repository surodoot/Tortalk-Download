package dev.gyeopdam.embeddedtor;
import java.util.HashSet;
import java.util.Set;
/** Strict, bounded JSON grammar guard before Android's deliberately lenient JSONTokener. */
final class JsonBounds {
    static boolean safe(String text) {
        if(text==null||text.length()>OnionApiRequest.MAX_RESPONSE_BYTES)return false;
        Parser p=new Parser(text);return p.value(0)&&p.end();
    }
    private static final class Parser {
        final String text;int at;
        Parser(String text){this.text=text;}
        void space(){while(at<text.length()){char c=text.charAt(at);if(c==' '||c=='\t'||c=='\r'||c=='\n')at++;else break;}}
        boolean end(){space();return at==text.length();}
        boolean take(char c){space();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
        boolean value(int depth){
            space();if(at>=text.length())return false;char c=text.charAt(at);
            if(c=='"')return string();
            if(c=='{'||c=='['){if(depth>=32)return false;return c=='{'?object(depth+1):array(depth+1);}
            if(c=='t')return literal("true");if(c=='f')return literal("false");if(c=='n')return literal("null");
            return number();
        }
        boolean object(int depth){
            at++;if(take('}'))return true;
            Set<String> keys=new HashSet<>();
            while(true){
                space();StringBuilder key=new StringBuilder();
                // Compare decoded names before JSONObject can overwrite an earlier value.
                if(!string(key)||!keys.add(key.toString())||!take(':')||!value(depth))return false;
                if(take('}'))return true;if(!take(','))return false;
            }
        }
        boolean array(int depth){
            at++;if(take(']'))return true;
            while(true){if(!value(depth))return false;if(take(']'))return true;if(!take(','))return false;}
        }
        boolean string(){return string(null);}
        boolean string(StringBuilder decoded){
            if(at>=text.length()||text.charAt(at++)!='"')return false;
            while(at<text.length()){
                char c=text.charAt(at++);if(c=='"')return true;if(c<32)return false;
                if(c=='\\'){
                    if(at>=text.length())return false;char e=text.charAt(at++);
                    switch(e){
                        case '"':case '\\':case '/':c=e;break;
                        case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;
                        case 'u':
                            int value=0;
                            for(int i=0;i<4;i++){
                                if(at>=text.length())return false;char h=text.charAt(at++);
                                int digit=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;
                                if(digit<0)return false;value=(value<<4)|digit;
                            }
                            c=(char)value;break;
                        default:return false;
                    }
                }
                if(decoded!=null)decoded.append(c);
            }return false;
        }
        boolean literal(String value){if(!text.regionMatches(at,value,0,value.length()))return false;at+=value.length();return true;}
        boolean digit(){return at<text.length()&&text.charAt(at)>='0'&&text.charAt(at)<='9';}
        boolean number(){
            if(at<text.length()&&text.charAt(at)=='-')at++;
            if(!digit())return false;
            if(text.charAt(at)=='0')at++;else while(digit())at++;
            if(at<text.length()&&text.charAt(at)=='.'){at++;if(!digit())return false;while(digit())at++;}
            if(at<text.length()&&(text.charAt(at)=='e'||text.charAt(at)=='E')){at++;if(at<text.length()&&(text.charAt(at)=='+'||text.charAt(at)=='-'))at++;if(!digit())return false;while(digit())at++;}
            return true;
        }
    }
}
