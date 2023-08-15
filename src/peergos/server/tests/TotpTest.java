package peergos.server.tests;

import com.eatthepath.otp.*;
import org.junit.*;
import peergos.shared.fingerprint.*;
import peergos.shared.io.ipfs.bases.Base32;
import peergos.shared.login.mfa.*;
import peergos.shared.zxing.*;
import peergos.shared.zxing.common.*;
import peergos.shared.zxing.qrcode.*;

import javax.crypto.spec.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;

public class TotpTest {

    @Test
    public void roundtrip() throws Exception {
        // Google authenticator is hard coded to this and will silently ignore attempts to use HmacSha256. Thanks Google!
        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, TotpKey.ALGORITHM);
        byte[] rawKey = new Base32().decode("W6AWIT4QDM3BIHILNZUHRLAQ7DWAC4IODJBUZWHQGKVKA3DVJQ6Q");
        Key key = new SecretKeySpec(rawKey, TotpKey.ALGORITHM);

        byte[] encoded = key.getEncoded();
        Instant now = Instant.now();
        Duration timeStep = totp.getTimeStep();
        String clientCode = totp.generateOneTimePasswordString(key, now);
        String serverCode = totp.generateOneTimePasswordString(key, now.plus(timeStep.dividedBy(2)));
        String serverCode2 = totp.generateOneTimePasswordString(key, now.minus(timeStep.dividedBy(2)));
        Assert.assertTrue(serverCode.equals(clientCode) || serverCode2.equals(clientCode));

        //  generate a QR code
        QRCodeWriter writer = new QRCodeWriter();
        String issuer = "peergos";
        String label = issuer + ":demo@peergos";
        String originalText = "otpauth://totp/" + label + "?secret=" + new Base32().encodeToString(encoded).replaceAll("=","")
                + "&issuer=" + issuer;

        BitMatrix result = writer.encode(originalText, BarcodeFormat.QR_CODE, 512, 512);
        byte[] png = FingerPrint.encodeToPng(0, result.getWidth(), result.getHeight(), result);
        Files.write(Paths.get("totp-qr-code.png"), png);
        System.out.println("Try scanning QR code with an app...");
    }
}
