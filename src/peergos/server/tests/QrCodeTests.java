package peergos.server.tests;

import org.junit.*;
import peergos.shared.zxing.*;
import peergos.shared.zxing.common.*;
import peergos.shared.zxing.qrcode.*;

import javax.imageio.*;
import java.awt.image.*;
import java.io.*;

public class QrCodeTests {

    @Test
    public void invertable() throws Exception {
        String original = "Peergos is amazing! So many cool features!";

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix result = writer.encode(original, BarcodeFormat.QR_CODE, 512, 512);
        BufferedImage bitmap = new BufferedImage(result.getWidth(), result.getHeight(), BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                if (result.get(x, y))
                    bitmap.setRGB(x, y, 0xff000000);
                else
                    bitmap.setRGB(x, y, 0xffffffff);
            }
        }
        File file = new File("qr-code.png");
        ImageIO.write(bitmap, "png", file);


        // now read back in
        BufferedImage read = ImageIO.read(file);

        int width = read.getWidth();
        int height = read.getHeight();
        int[] pixels = read.getRGB(0, 0, width, height, null, 0, width);
        // This source doesn't handle rotations or dilations
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);

        BinaryBitmap readBitmap = new BinaryBitmap(new HybridBinarizer(source));
        QRCodeReader reader = new QRCodeReader();
        Result decoded = reader.decode(readBitmap);
        String text = decoded.getText();
        Assert.assertTrue("Round trip", text.equals(original));
    }
}
