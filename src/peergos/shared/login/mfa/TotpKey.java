package peergos.shared.login.mfa;

import jsinterop.annotations.JsType;
import peergos.shared.fingerprint.FingerPrint;
import peergos.shared.io.ipfs.multibase.binary.*;
import peergos.shared.zxing.BarcodeFormat;
import peergos.shared.zxing.common.BitMatrix;
import peergos.shared.zxing.qrcode.QRCodeWriter;

import java.util.Base64;

@JsType
public class TotpKey {
    public static final String ALGORITHM = "HmacSHA1"; // Can't change this because google authenticator ignores the algorithm!!

    public final byte[] key;

    public TotpKey(byte[] key) {
        this.key = key;
    }

    public String encode() {
        return new Base32().encodeToString(key).replaceAll("=","");
    }

    public static TotpKey fromString(String base32) {
        return new TotpKey(new Base32().decode(base32));
    }

    public String getQRCode(String username) {
        QRCodeWriter writer = new QRCodeWriter();
        String issuer = "peergos";
        String label = issuer + ":" + username + "@peergos";
        String originalText = "otpauth://totp/" + label + "?secret=" + new Base32().encodeToString(key).replaceAll("=","")
                + "&issuer=" + issuer;
        try {
            BitMatrix result = writer.encode(originalText, BarcodeFormat.QR_CODE, 512, 512);
            byte[] png = FingerPrint.encodeToPng(0, result.getWidth(), result.getHeight(), result);
            String base64Data = Base64.getEncoder().encodeToString(png);
            return "data:image/png;base64," + base64Data;
        } catch(Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}
