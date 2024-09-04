package peergos.shared;

import jsinterop.annotations.JsMethod;
import peergos.shared.zxing.BarcodeFormat;
import peergos.shared.zxing.BinaryBitmap;
import peergos.shared.zxing.RGBLuminanceSource;
import peergos.shared.zxing.WriterException;
import peergos.shared.zxing.common.BitMatrix;
import peergos.shared.zxing.common.HybridBinarizer;
import peergos.shared.zxing.qrcode.QRCodeReader;
import peergos.shared.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.util.*;

public class SecretLinkQRCode {

    private final String  contents;

    private SecretLinkQRCode(String contents) {
        this.contents = contents;
    }

    @JsMethod
    public String getBase64Thumbnail() {
        String base64Data = Base64.getEncoder().encodeToString(getQrCodeData());
        return "data:image/png;base64," + base64Data;
    }

    @JsMethod
    public static SecretLinkQRCode generate(String link) {
            return new SecretLinkQRCode(link);
    }

    public static String decodeFromPixels(int[] pixels, int width, int height) {
        // This source doesn't handle rotations or dilations
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);

        BinaryBitmap readBitmap = new BinaryBitmap(new HybridBinarizer(source));
        QRCodeReader reader = new QRCodeReader();
        try {
            return reader.decode(readBitmap).getText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] getQrCodeData() {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix result = writer.encode(contents, BarcodeFormat.QR_CODE, 512, 512);
            return QRCodeEncoder.encodeToPng(QRCodeEncoder.BW_MODE, result.getWidth(), result.getHeight(), result);
        } catch (WriterException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SecretLinkQRCode that = (SecretLinkQRCode) o;
        return contents.equals(that.contents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contents);
    }

}
