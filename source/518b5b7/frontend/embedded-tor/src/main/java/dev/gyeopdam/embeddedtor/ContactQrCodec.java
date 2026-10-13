package dev.gyeopdam.embeddedtor;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;

/** Offline QR-only pixel codec. No files, network, Android, camera or intent integration. */
public final class ContactQrCodec {
    public static final int MAX_PIXELS = 1_000_000;
    public static final int MAX_DIMENSION = 2048;
    public static final int MIN_ENCODE_SIDE = 256;
    private ContactQrCodec() { }

    /**
     * Call before image decompression/allocation as well as before decode. This
     * codec accepts already-decoded pixels; compressed-image byte limits and
     * header inspection are the native importer's responsibility.
     */
    public static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("CONTACT_QR_BOUNDS");
        }
    }

    /** Opaque black/white ARGB pixels, in row-major order, exactly side squared. */
    public static int[] encode(ContactCard card, int side) {
        validateDimensions(side, side);
        if (side < MIN_ENCODE_SIDE) throw new IllegalArgumentException("CONTACT_QR_BOUNDS");
        if (card == null) throw invalid();
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "US-ASCII");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 4);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(card.encode(), BarcodeFormat.QR_CODE,
                    side, side, hints);
            // QRCodeWriter can enlarge too-small requests. Never return an unexpected allocation/size.
            if (matrix.getWidth() != side || matrix.getHeight() != side) throw invalid();
            int[] pixels = new int[side * side];
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    pixels[y * side + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
                }
            }
            return pixels;
        } catch (WriterException | RuntimeException rejected) {
            throw invalid();
        }
    }

    /** One complete QR payload per decode; errors have fixed messages and never expose input. */
    public static ContactCard decode(int width, int height, int[] pixels, String expectedOrigin) {
        validateDimensions(width, height);
        if (pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException("CONTACT_QR_BOUNDS");
        }
        String payload;
        try {
            // Deliberately use QRCodeReader, never MultiFormatReader or an actionable-result parser.
            BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(width, height, pixels)));
            Result result = new QRCodeReader().decode(image);
            Map<ResultMetadataType, Object> metadata = result.getResultMetadata();
            if (result.getBarcodeFormat() != BarcodeFormat.QR_CODE
                    || (metadata != null && (metadata.containsKey(ResultMetadataType.STRUCTURED_APPEND_SEQUENCE)
                    || metadata.containsKey(ResultMetadataType.STRUCTURED_APPEND_PARITY)))) throw invalid();
            payload = result.getText();
        } catch (ReaderException | RuntimeException rejected) {
            throw invalid();
        }
        return ContactCard.decode(payload, expectedOrigin);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONTACT_QR_INVALID");
    }
}
