package peergos.server.user;

import peergos.server.util.Logging;
import peergos.shared.cbor.CborObject;
import peergos.shared.user.fs.*;

import javax.imageio.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.*;

public class JavaImageThumbnailer implements ThumbnailGenerator.Generator {
    private final static int THUMBNAIL_SIZE = 400;

    public Optional<Thumbnail> generateThumbnail(byte[] imageBlob) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBlob));
            if (image == null) // e.g. svg files
                return Optional.empty();
            int size = THUMBNAIL_SIZE;
            int height = image.getHeight();
            int width = image.getWidth();
            boolean tall = height > width;
            int canvasWidth = tall ? size : width*size/height;
            int canvasHeight = tall ? height*size/width : size;
            BufferedImage thumbnailImage = new BufferedImage(THUMBNAIL_SIZE, THUMBNAIL_SIZE, image.getType());
            Graphics2D g = thumbnailImage.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int x = tall ? 0 : -(canvasWidth - THUMBNAIL_SIZE) / 2;
            int y = tall ? -(canvasHeight - THUMBNAIL_SIZE) / 2 : 0;
            g.drawImage(image, x, y, canvasWidth, canvasHeight, null);
            g.dispose();
            // the centre square is the same however the image is turned
            thumbnailImage = upright(thumbnailImage, exifOrientation(imageBlob));

            // try webp first
            try {
                ByteArrayOutputStream webp = new ByteArrayOutputStream();
                ImageIO.write(thumbnailImage, "webp", webp);
                webp.close();
                if (webp.size() > 0)
                    return Optional.of(new Thumbnail("image/webp", webp.toByteArray()));
            } catch (Throwable t) {
                // webp library doesn't support all OS+ARCH combos
                Logging.LOG().log(Level.WARNING, t.getMessage(), t);
            }

            // try jpeg
            try {
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                ImageIO.write(thumbnailImage, "JPG", jpg);
                jpg.close();
                if (jpg.size() > 0)
                    return Optional.of(new Thumbnail("image/jpeg", jpg.toByteArray()));
            } catch (Exception e) {}

            // try png
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(thumbnailImage, "png", png);
            png.close();
            return Optional.of(new Thumbnail("image/png", png.toByteArray()));
        } catch (Throwable t) {
            Logging.LOG().log(Level.WARNING, t.getMessage(), t);
        }
        return Optional.empty();
    }

    /** The Orientation (tag 274) in a JPEG's Exif segment, 1 to 8, or 1 where there is none to read.
     *  ImageIO hands over the pixels as stored, while a browser shows them turned the way this says.
     */
    public static int exifOrientation(byte[] jpeg) {
        try {
            ByteBuffer b = ByteBuffer.wrap(jpeg);
            if (b.getShort(0) != (short) 0xFFD8)
                return 1;
            // the segments before the first scan, each a marker and its length
            for (int at = 2; b.get(at) == (byte) 0xFF; at += 2 + (b.getShort(at + 2) & 0xFFFF)) {
                while (b.get(at + 1) == (byte) 0xFF) // fill bytes before the marker (T.81 B.1.1.2)
                    at++;
                if (b.get(at + 1) == (byte) 0xDA)
                    break;
                if (b.get(at + 1) != (byte) 0xE1 || b.getInt(at + 4) != 0x45786966 || b.getShort(at + 8) != 0) // APP1 "Exif\0\0"
                    continue;
                ByteBuffer tiff = b.slice(at + 10, (b.getShort(at + 2) & 0xFFFF) - 8);
                short order = tiff.getShort(0);
                if (order != 0x4949 && order != 0x4D4D) // II or MM
                    return 1;
                tiff.order(order == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                if (tiff.getShort(2) != 42)
                    return 1;
                int ifd0 = tiff.getInt(4);
                for (int i = 0; i < (tiff.getShort(ifd0) & 0xFFFF); i++) {
                    int entry = ifd0 + 2 + 12 * i;
                    if (tiff.getShort(entry) != 274)
                        continue;
                    // one SHORT, held in the entry itself
                    int value = tiff.getShort(entry + 8);
                    return tiff.getShort(entry + 2) == 3 && tiff.getInt(entry + 4) == 1 && value >= 1 && value <= 8 ? value : 1;
                }
                return 1;
            }
        } catch (IndexOutOfBoundsException malformed) {}
        return 1;
    }

    /** The image as it is meant to be seen, given how its pixels are stored (TIFF 6.0 Orientation). */
    private static BufferedImage upright(BufferedImage image, int orientation) {
        int w = image.getWidth(), h = image.getHeight();
        // where each stored pixel is shown
        AffineTransform shown = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> null;
        };
        return shown == null ? image : new AffineTransformOp(shown, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(image, null);
    }
}
