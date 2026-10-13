package dev.gyeopdam.embeddedtor;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Prepared in-memory Android bitmap checks only; no provider, camera, Tor, account or key creation. */
public final class AndroidQrRuntimeChecks {
    public static void run()throws Exception {
        String origin="http://"+repeat('a',56)+".onion";byte[] serialized=new byte[33];serialized[0]=5;
        for(int i=1;i<serialized.length;i++)serialized[i]=(byte)i;
        ContactCard card=ContactCard.create(origin,"demo-account-qr","demo-device-qr",1,Base64.getEncoder().encodeToString(serialized));
        Bitmap bitmap=ContactQrImage.render(card);
        byte[] png;
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            require(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes),"QR_PNG_ENCODE");png=bytes.toByteArray();
        }finally{bitmap.recycle();}
        require(card.encode().equals(ContactQrImage.decode(png,origin).encode()),"ANDROID_QR_PNG_ROUNDTRIP");
        Bitmap source=ContactQrImage.render(card),large=Bitmap.createScaledBitmap(source,1024,1024,false);byte[] sampled,jpeg;
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream();ByteArrayOutputStream jpegBytes=new ByteArrayOutputStream()){
            require(large.compress(Bitmap.CompressFormat.PNG,100,bytes),"QR_LARGE_FIXTURE_ENCODE");sampled=bytes.toByteArray();
            require(source.compress(Bitmap.CompressFormat.JPEG,100,jpegBytes),"QR_JPEG_FIXTURE_ENCODE");jpeg=jpegBytes.toByteArray();
        }finally{source.recycle();large.recycle();}
        require(card.encode().equals(ContactQrImage.decode(sampled,origin).encode()),"ANDROID_QR_DOWNSAMPLED_PNG");
        require(card.encode().equals(ContactQrImage.decode(jpeg,origin).encode()),"ANDROID_QR_JPEG_ROUNDTRIP");
        reject(()->ContactQrImage.decode(png,"http://"+repeat('b',56)+".onion"),"QR_OTHER_ORIGIN_REJECTED");
        reject(()->ContactQrImage.decode(new byte[ContactQrImage.MAX_ENCODED_BYTES+1],origin),"QR_COMPRESSED_BOUND");
        reject(()->ContactQrImage.decode(new byte[]{1,2,3,4},origin),"QR_INVALID_IMAGE_REJECTED");
        Bitmap wide=Bitmap.createBitmap(8193,1,Bitmap.Config.ARGB_8888);byte[] oversized;
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            require(wide.compress(Bitmap.CompressFormat.PNG,100,bytes),"QR_WIDE_FIXTURE_ENCODE");oversized=bytes.toByteArray();
        }finally{wide.recycle();}
        reject(()->ContactQrImage.decode(oversized,origin),"QR_HEADER_BOUND_BEFORE_DECOMPRESSION");
        CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();boolean stopped=false;
        try{ContactQrImage.read(null,Uri.parse("content://synthetic/qr"),origin,cancelled);}catch(OperationCanceledException expected){stopped=true;}
        require(stopped,"QR_CANCEL_BEFORE_PROVIDER");
        checkBlockedStreamCancellation();
    }
    private static void checkBlockedStreamCancellation()throws Exception {
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),finished=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        java.io.InputStream blocked=new java.io.InputStream(){
            @Override public synchronized int read()throws IOException {entered.countDown();while(!closed.get()){try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("CANCELLED");}}throw new IOException("CLOSED");}
            @Override public synchronized void close(){closed.set(true);notifyAll();}
        };
        CancellationSignal signal=new CancellationSignal();Thread worker=new Thread(()->{
            try{ContactQrImage.readEncoded(blocked,signal);}catch(IOException|OperationCanceledException expected){}finally{finished.countDown();}
        },"qr-cancellation-fixture");worker.setDaemon(true);worker.start();
        try{require(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"QR_BLOCKED_READ_ENTERED");signal.cancel();
            require(finished.await(5,java.util.concurrent.TimeUnit.SECONDS)&&closed.get(),"QR_CANCEL_CLOSES_BLOCKED_STREAM");
        }finally{blocked.close();worker.interrupt();}
    }
    private interface Read {void run()throws Exception;}
    private static void reject(Read read,String code)throws Exception {boolean rejected=false;try{read.run();}catch(IOException|IllegalArgumentException expected){rejected=true;}require(rejected,code);}
    private static String repeat(char c,int n){char[] chars=new char[n];java.util.Arrays.fill(chars,c);return new String(chars);}
    private static void require(boolean condition,String code){if(!condition)throw new AssertionError(code);}
}
