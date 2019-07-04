package peergos.server.tests;

import org.junit.*;
import peergos.shared.zxing.*;
import peergos.shared.zxing.common.*;
import peergos.shared.zxing.qrcode.*;

import javax.imageio.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;

public class QrCodeTests {

    @Test
    public void invertable() throws Exception {
        String originalText = "Peergos is amazing! So many cool features!";

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix result = writer.encode(originalText, BarcodeFormat.QR_CODE, 512, 512);
        BufferedImage original = new BufferedImage(result.getWidth(), result.getHeight(), BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                if (result.get(x, y))
                    original.setRGB(x, y, 0xff000000);
                else
                    original.setRGB(x, y, 0xffffffff);
            }
        }
        File file = new File("qr-code.png");
        ImageIO.write(original, "png", file);

        // now read back in
        BufferedImage read = ImageIO.read(file);

        int width = read.getWidth();
        int height = read.getHeight();

        Result decoded = decodeRGB(read);
        String text = decoded.getText();
        Assert.assertTrue("Round trip perfect scan", text.equals(originalText));

        // Now try a rotated and dilated image
        int scaledHeight = (int) (height * 0.9);
        int scaledWidth = (int) (width * 0.9);
        AffineTransform transform = AffineTransform.getTranslateInstance((scaledHeight-scaledWidth)/2, (scaledWidth-scaledHeight)/2);
        int degrees = 10;
        transform.rotate(Math.toRadians(degrees), scaledWidth/2, scaledHeight/2);
        transform.scale(.9, .9);
        AffineTransformOp operation = new AffineTransformOp(transform, AffineTransformOp.TYPE_BICUBIC);
        BufferedImage transformedImage = operation.createCompatibleDestImage(original, original.getColorModel());
        BufferedImage transformed = operation.filter(original, transformedImage);
        ImageIO.write(transformed, "png", new File("qr-code-transformed.png"));

        // now decode
        Result fromTransform = decodeRGB(transformed);
        Assert.assertTrue("Decode transformed scan", fromTransform.getText().equals(originalText));
    }

    private static Result decodeRGB(BufferedImage in) throws Exception {
        int width = in.getWidth();
        int height = in.getHeight();
        int[] pixels = in.getRGB(0, 0, width, height, null, 0, width);
        // This source doesn't handle rotations or dilations
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);

        BinaryBitmap readBitmap = new BinaryBitmap(new HybridBinarizer(source));
        QRCodeReader reader = new QRCodeReader();
        return reader.decode(readBitmap);
    }
}
