package dev.gyeopdam.embeddedtor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Canonical native-only recovery checkpoint; phase zero is an acknowledged, secret-free tombstone. */
final class PendingDeviceRecoverySchema {
    static final int MAX_BYTES=4096;
    private static final int MAGIC=0x54545231,VERSION=1;
    private static final int[] LIMITS={69,80,80,80,51,51};
    static final class Record {final String[] fields;final boolean confirmed;Record(String[] fields,boolean confirmed){this.fields=fields;this.confirmed=confirmed;}}
    static boolean id(String value){return value!=null&&value.length()<=80&&value.matches("demo-[a-z0-9-]{1,75}");}
    static void validate(String[] fields){
        if(fields==null||fields.length!=6||!SessionSchema.isCanonicalOrigin(fields[0])||!id(fields[1])||!id(fields[2])||!id(fields[3])
                ||!SessionSchema.token(fields[4],"gd_acct_")||!SessionSchema.token(fields[5],"gd_acct_")||fields[4].equals(fields[5]))throw new IllegalArgumentException("INVALID_DEVICE_RECOVERY");
    }
    static byte[] encode(String[] fields,boolean confirmed)throws IOException {
        if(fields==null){if(confirmed)throw invalid();return ByteBuffer.allocate(12).putInt(MAGIC).putInt(VERSION).putInt(0).array();}
        try{validate(fields);}catch(IllegalArgumentException bad){throw invalid();}
        int length=24;for(String value:fields)length+=value.length();ByteBuffer result=ByteBuffer.allocate(length).putInt(MAGIC).putInt(VERSION).putInt(confirmed?2:1);
        for(String value:fields){result.putShort((short)value.length());for(int i=0;i<value.length();i++)result.put((byte)value.charAt(i));}return result.array();
    }
    static Record decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length<12||bytes.length>MAX_BYTES)throw invalid();ByteBuffer input=ByteBuffer.wrap(bytes);
        if(input.getInt()!=MAGIC||input.getInt()!=VERSION)throw invalid();int phase=input.getInt();if(phase==0){if(input.hasRemaining())throw invalid();return new Record(null,false);}if(phase!=1&&phase!=2)throw invalid();
        String[] fields=new String[6];for(int i=0;i<fields.length;i++){
            if(input.remaining()<2)throw invalid();int length=input.getShort()&65535;if(length==0||length>LIMITS[i]||input.remaining()<length)throw invalid();byte[] value=new byte[length];
            try{input.get(value);for(byte b:value)if(b<33||b>126)throw invalid();fields[i]=new String(value,StandardCharsets.US_ASCII);}finally{Arrays.fill(value,(byte)0);}
        }
        if(input.hasRemaining())throw invalid();try{validate(fields);}catch(IllegalArgumentException bad){throw invalid();}return new Record(fields,phase==2);
    }
    private static IOException invalid(){return new IOException("CORRUPT_DEVICE_RECOVERY");}
    private PendingDeviceRecoverySchema() { }
}
