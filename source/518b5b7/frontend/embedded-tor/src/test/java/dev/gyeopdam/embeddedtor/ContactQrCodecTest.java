package dev.gyeopdam.embeddedtor;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.gyeopdam.embeddedtor.ContactCardTest.*;

public final class ContactQrCodecTest {
    private static ContactCard decode(int side, int[] pixels) {
        return ContactQrCodec.decode(side, side, pixels, ORIGIN);
    }

    private static int[] pixels(BitMatrix matrix) {
        int width = matrix.getWidth(), height = matrix.getHeight();
        int[] result = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) result[y * width + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        }
        return result;
    }

    private static int[] rotate(int[] original, int side) {
        int[] result = new int[original.length];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) result[x * side + (side - 1 - y)] = original[y * side + x];
        }
        return result;
    }

    @Test public void publicCardRoundTripsAtBoundedSizes() {
        for (int side : new int[]{256, 384, 768, 1000}) {
            int[] result = ContactQrCodec.encode(card(), side);
            assertEquals(side * side, result.length);
            assertEquals(card().encode(), decode(side, result).encode());
            assertEquals(0xffffffff, result[0]);
            for (int pixel : result) assertTrue(pixel == 0xff000000 || pixel == 0xffffffff);
        }
    }

    @Test public void longestCardRoundTripsAtMinimumEncodeSize() {
        ContactCard longest = ContactCard.create(ORIGIN, "a".repeat(80), "b".repeat(80), 127, IDENTITY);
        assertEquals(longest.encode(), decode(256, ContactQrCodec.encode(longest, 256)).encode());
    }

    @Test public void allQuarterTurnRotationsAreReadable() {
        int[] result = ContactQrCodec.encode(card(), 384);
        for (int turn = 0; turn < 4; turn++) {
            assertEquals(card().encode(), decode(384, result).encode());
            result = rotate(result, 384);
        }
    }

    @Test public void highErrorCorrectionSurvivesSmallCentralDamage() throws Exception {
        int side = 384;
        int[] result = ContactQrCodec.encode(card(), side);
        Result qr = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(side, side, result))));
        assertEquals("H", qr.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL));
        for (int y = side / 2; y < side / 2 + 8; y++) {
            for (int x = side / 2; x < side / 2 + 8; x++) result[y * side + x] = 0xffffffff;
        }
        assertEquals(card().encode(), decode(side, result).encode());
    }

    @Test public void imageCanContainAQuietBorderAndNonSquareDimensions() {
        int[] square = ContactQrCodec.encode(card(), 384);
        int width = 480, height = 432;
        int[] bordered = new int[width * height];
        Arrays.fill(bordered, 0xffffffff);
        for (int y = 0; y < 384; y++) System.arraycopy(square, y * 384, bordered, (y + 24) * width + 48, 384);
        assertEquals(card().encode(), ContactQrCodec.decode(width, height, bordered, ORIGIN).encode());
    }

    @Test public void rejectsQrForAnotherOrigin() {
        int[] result = ContactQrCodec.encode(card(), 384);
        rejects("CONTACT_ORIGIN_MISMATCH", () -> ContactQrCodec.decode(384, 384, result, OTHER_ORIGIN));
    }

    @Test public void rejectsOtherBarcodeFormatsEvenWhenTheirContentIsAValidCard() throws Exception {
        BitMatrix dataMatrix = new DataMatrixWriter().encode(card().encode(), BarcodeFormat.DATA_MATRIX, 384, 384);
        rejects("CONTACT_QR_INVALID", () -> ContactQrCodec.decode(dataMatrix.getWidth(), dataMatrix.getHeight(), pixels(dataMatrix), ORIGIN));
    }

    @Test public void rejectsBlankAndNoiseImagesWithFixedErrors() {
        int[] blank = new int[384 * 384];
        Arrays.fill(blank, 0xffffffff);
        rejects("CONTACT_QR_INVALID", () -> decode(384, blank));
        int[] noise = new int[384 * 384];
        java.util.Random syntheticNoise = new java.util.Random(9383);
        for (int i = 0; i < noise.length; i++) noise[i] = syntheticNoise.nextBoolean() ? 0xff000000 : 0xffffffff;
        rejects("CONTACT_QR_INVALID", () -> decode(384, noise));
    }

    @Test public void decodedPayloadStillPassesTheStrictContactParser() throws Exception {
        for (String payload : new String[]{"https://example.org/?session_token=synthetic", "WIFI:S:private;P:secret;;",
                card().encode() + "\nsession_token=synthetic", withField(5, "private-secret"),
                "x".repeat(ContactCard.MAX_PAYLOAD_CHARS + 1)}) {
            BitMatrix qr = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512);
            rejects("CONTACT_INVALID", () -> decode(512, pixels(qr)));
        }
    }

    @Test public void dimensionsRejectDecompressionAndIntegerOverflowBeforePixelAllocation() {
        for (int[] size : new int[][]{{0, 1}, {1, 0}, {-1, 256}, {256, -1}, {2049, 1}, {1, 2049},
                {1001, 1000}, {1000, 1001}, {2048, 489}, {2048, 2048}, {Integer.MAX_VALUE, Integer.MAX_VALUE},
                {65536, 65536}, {Integer.MIN_VALUE, -1}}) {
            rejects("CONTACT_QR_BOUNDS", () -> ContactQrCodec.validateDimensions(size[0], size[1]));
            rejects("CONTACT_QR_BOUNDS", () -> ContactQrCodec.decode(size[0], size[1], new int[0], ORIGIN));
        }
        ContactQrCodec.validateDimensions(1000, 1000);
        ContactQrCodec.validateDimensions(2048, 488);
        ContactQrCodec.validateDimensions(488, 2048);
    }

    @Test public void pixelsMustMatchTheExactValidatedDimensions() {
        for (int[] data : new int[][]{null, new int[0], new int[255 * 256], new int[257 * 256]}) {
            rejects("CONTACT_QR_BOUNDS", () -> ContactQrCodec.decode(256, 256, data, ORIGIN));
        }
    }

    @Test public void encodingRejectsMissingCardAndUnboundedOrUnreadableSize() {
        for (int size : new int[]{Integer.MIN_VALUE, -1, 0, 1, 255, 1001, 2048, Integer.MAX_VALUE}) {
            rejects("CONTACT_QR_BOUNDS", () -> ContactQrCodec.encode(card(), size));
        }
        rejects("CONTACT_QR_INVALID", () -> ContactQrCodec.encode(null, 256));
    }
}
