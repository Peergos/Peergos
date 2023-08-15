package peergos.shared.login.mfa;

import jsinterop.annotations.JsType;
import peergos.shared.fingerprint.FingerPrint;
import peergos.shared.io.ipfs.bases.Base32;
import peergos.shared.zxing.BarcodeFormat;
import peergos.shared.zxing.common.BitMatrix;
import peergos.shared.zxing.qrcode.QRCodeWriter;

import java.util.Base64;

@JsType
public class TotpKey {
    public static final String ALGORITHM = "HmacSHA1"; // Can't change this because google authenticator ignores the algorithm!!

    public final byte[] credentialId, key;

    public TotpKey(byte[] credentialId, byte[] key) {
        this.credentialId = credentialId;
        this.key = key;
    }

    public String encode() {
        return base32(credentialId) + ":" + base32(key);
    }

    private static String base32(byte[] in) {
        return new Base32().encodeToString(in).replaceAll("=","");
    }

    public static TotpKey fromString(String encoded) {
        int endIndex = encoded.indexOf(":");
        String base32credid = encoded.substring(0, endIndex);
        String base32key = encoded.substring(endIndex + 1, encoded.length());
        return new TotpKey(new Base32().decode(base32credid), new Base32().decode(base32key));
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
