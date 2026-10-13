package dev.gyeopdam.embeddedtor;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Native, explicit user-selected image input. Picker requests local-only; providers may do their own IO. */
final class ContactQrImage {
    static final int MAX_ENCODED_BYTES=4*1024*1024;
    private ContactQrImage() { }
    static Bitmap render(ContactCard card){
        int side=512;return Bitmap.createBitmap(ContactQrCodec.encode(card,side),side,side,Bitmap.Config.ARGB_8888);
    }
    static ContactCard read(ContentResolver resolver,Uri uri,String expectedOrigin,CancellationSignal cancellation)throws IOException {
        if(uri==null||!"content".equals(uri.getScheme()))throw invalid();
        cancellation.throwIfCanceled();
        try(AssetFileDescriptor descriptor=resolver.openAssetFileDescriptor(uri,"r",cancellation)){
            if(descriptor==null||descriptor.getDeclaredLength()>MAX_ENCODED_BYTES)throw invalid();
            try(InputStream input=descriptor.createInputStream()){
                return decode(readEncoded(input,cancellation),expectedOrigin);
            }
        }
    }
    static byte[] readEncoded(InputStream input,CancellationSignal cancellation)throws IOException {
        if(input==null||cancellation==null)throw invalid();
        // Closing the acquired stream is essential: a provider may block inside read().
        cancellation.setOnCancelListener(()->{try{input.close();}catch(IOException ignored){}});
        try(ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];long deadline=android.os.SystemClock.elapsedRealtime()+15000;
            for(;;){cancellation.throwIfCanceled();if(android.os.SystemClock.elapsedRealtime()>=deadline)throw invalid();
                int count=input.read(buffer);if(count==-1)break;if(count==0)continue;if(output.size()+count>MAX_ENCODED_BYTES)throw invalid();output.write(buffer,0,count);}
            cancellation.throwIfCanceled();return output.toByteArray();
        }finally{cancellation.setOnCancelListener(null);}
    }
    static ContactCard decode(byte[] encoded,String expectedOrigin)throws IOException {
        if(encoded==null||encoded.length==0||encoded.length>MAX_ENCODED_BYTES)throw invalid();
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(encoded,0,encoded.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>8192||bounds.outHeight>8192
                ||!("image/png".equals(bounds.outMimeType)||"image/jpeg".equals(bounds.outMimeType)))throw invalid();
        BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        options.inSampleSize=1;
        while((long)((bounds.outWidth+options.inSampleSize-1)/options.inSampleSize)*((bounds.outHeight+options.inSampleSize-1)/options.inSampleSize)>1000000L
                ||(bounds.outWidth+options.inSampleSize-1)/options.inSampleSize>2048||(bounds.outHeight+options.inSampleSize-1)/options.inSampleSize>2048)options.inSampleSize*=2;
        ContactQrCodec.validateDimensions((bounds.outWidth+options.inSampleSize-1)/options.inSampleSize,(bounds.outHeight+options.inSampleSize-1)/options.inSampleSize);
        Bitmap bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
        if(bitmap==null)throw invalid();
        try{int width=bitmap.getWidth(),height=bitmap.getHeight();ContactQrCodec.validateDimensions(width,height);
            int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);
            return ContactQrCodec.decode(width,height,pixels,expectedOrigin);
        }catch(IllegalArgumentException malformed){throw invalid();}finally{bitmap.recycle();}
    }
    private static IOException invalid(){return new IOException("INVALID_CONTACT_QR_IMAGE");}
}
