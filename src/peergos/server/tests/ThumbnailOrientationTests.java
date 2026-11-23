package peergos.server.tests;

import org.junit.*;
import peergos.server.user.*;
import peergos.server.util.*;
import peergos.shared.user.fs.*;

import javax.imageio.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** A camera stores a portrait photo as landscape pixels and an EXIF Orientation saying how to turn
 *  them. A browser shows the photo turned, so its thumbnail has to be turned the same way.
 */
public class ThumbnailOrientationTests {

    private static final int W = 300, H = 400;

    /** Upright: red at the top left and green at the top right of the centre square a thumbnail keeps. */
    private static BufferedImage upright() {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.RED);
        g.fillRect(10, 60, 50, 50);
        g.setColor(Color.GREEN);
        g.fillRect(240, 60, 50, 50);
        g.dispose();
        return image;
    }

    /** The upright picture stored the way TIFF 6.0 says an orientation describes: which visual side
     *  the stored 0th row and 0th column are. */
    private static BufferedImage stored(int orientation) {
        BufferedImage up = upright();
        boolean turned = orientation >= 5;
        BufferedImage out = new BufferedImage(turned ? H : W, turned ? W : H, BufferedImage.TYPE_INT_RGB);
        for (int r = 0; r < out.getHeight(); r++)
            for (int c = 0; c < out.getWidth(); c++) {
                int x = switch (orientation) {
                    case 1, 4 -> c;            // 0th column is the visual left
                    case 2, 3 -> W - 1 - c;    // the visual right
                    case 5, 8 -> r;            // 0th row is the visual left
                    default -> W - 1 - r;      // 6, 7: the visual right
                };
                int y = switch (orientation) {
                    case 1, 2 -> r;            // 0th row is the visual top
                    case 3, 4 -> H - 1 - r;    // the visual bottom
                    case 5, 6 -> c;            // 0th column is the visual top
                    default -> H - 1 - c;      // 7, 8: the visual bottom
                };
                out.setRGB(c, r, up.getRGB(x, y));
            }
        return out;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** An APP1 Exif segment holding IFD0 with an ImageWidth and then the given Orientation entry. */
    private static byte[] exif(ByteOrder order, int type, int count, int orientation) {
        byte mark = order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M';
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4).order(order);
        tiff.put(mark).put(mark).putShort((short) 42).putInt(8).putShort((short) 2);
        tiff.putShort((short) 256).putShort((short) 4).putInt(1).putInt(W);
        tiff.putShort((short) 274).putShort((short) type).putInt(count).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        return segment(tiff.array());
    }

    private static byte[] segment(byte[] tiff) {
        ByteBuffer app1 = ByteBuffer.allocate(4 + 6 + tiff.length);
        app1.putShort((short) 0xFFE1).putShort((short) (2 + 6 + tiff.length)).put("Exif\0\0".getBytes()).put(tiff);
        return app1.array();
    }

    /** The jpeg with the segment put after its first one, the JFIF APP0, as cameras that write both do. */
    private static byte[] withSegment(byte[] jpeg, byte[] segment) {
        int afterApp0 = 4 + ((jpeg[4] & 0xFF) << 8 | (jpeg[5] & 0xFF));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, afterApp0);
        out.writeBytes(segment);
        out.write(jpeg, afterApp0, jpeg.length - afterApp0);
        return out.toByteArray();
    }

    private static byte[] photo(int orientation, ByteOrder order) throws IOException {
        return withSegment(jpeg(stored(orientation)), exif(order, 3, 1, orientation));
    }

    private static void assertUpright(String what, Optional<Thumbnail> thumbnail) throws IOException {
        Assert.assertTrue(what + " has a thumbnail", thumbnail.isPresent());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumbnail.get().data));
        Assert.assertNotNull(what + " has a thumbnail we can read, not " + thumbnail.get().mimeType, image);
        int size = image.getWidth();
        Color topLeft = new Color(image.getRGB(size / 8, size / 8)), topRight = new Color(image.getRGB(size * 7 / 8, size / 8));
        Assert.assertTrue(what + ": red at the top left, not " + topLeft, topLeft.getRed() > 200 && topLeft.getGreen() < 80);
        Assert.assertTrue(what + ": green at the top right, not " + topRight, topRight.getGreen() > 200 && topRight.getRed() < 80);
    }

    @Test
    public void everyOrientationIsTurnedUpright() throws IOException {
        JavaImageThumbnailer thumbnailer = new JavaImageThumbnailer();
        for (ByteOrder order : List.of(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN))
            for (int orientation = 1; orientation <= 8; orientation++)
                assertUpright("orientation " + orientation + " " + order, thumbnailer.generateThumbnail(photo(orientation, order)));
    }

    /** ffmpeg, which the desktop app tries first, draws a jpeg as stored, so an oriented one has to go
     *  to the Java thumbnailer: ImageIO can't read ffmpeg's WebP, so a thumbnail read here came from Java. */
    @Test
    public void theDesktopThumbnailerTurnsThemToo() throws IOException {
        JvmThumbnailer.initJava();
        for (int orientation = 2; orientation <= 8; orientation++)
            assertUpright("orientation " + orientation, ThumbnailGenerator.get().generateThumbnail(photo(orientation, ByteOrder.BIG_ENDIAN)));
    }

    /** Fill bytes before a marker are allowed; whatever is really wrong with the Exif, the thumbnail is
     *  still made, from the pixels as stored. */
    @Test
    public void malformedExifLeavesThePixelsAsStored() throws IOException {
        JavaImageThumbnailer thumbnailer = new JavaImageThumbnailer();
        byte[] plain = jpeg(stored(6));
        byte[] expected = thumbnailer.generateThumbnail(plain).get().data;
        // offsets in it: 10 the byte order, 14 where IFD0 is, 18 its entry count, 32 the orientation's tag
        byte[] good = exif(ByteOrder.BIG_ENDIAN, 3, 1, 6);
        byte[] filled = new byte[good.length + 1];
        filled[0] = (byte) 0xFF; // a fill byte before the marker is allowed (T.81 B.1.1.2)
        System.arraycopy(good, 0, filled, 1, good.length);
        assertUpright("a fill byte before the Exif marker", thumbnailer.generateThumbnail(withSegment(plain, filled)));
        List<byte[]> segments = new ArrayList<>(List.of(
                exif(ByteOrder.BIG_ENDIAN, 3, 1, 1),
                exif(ByteOrder.BIG_ENDIAN, 3, 1, 0),
                exif(ByteOrder.BIG_ENDIAN, 3, 1, 9),
                exif(ByteOrder.BIG_ENDIAN, 4, 1, 6),   // a LONG
                exif(ByteOrder.BIG_ENDIAN, 3, 2, 6))); // two of them
        byte[] badOrder = good.clone();
        badOrder[10] = 'X';
        segments.add(badOrder);
        byte[] ifdOutside = good.clone();
        ByteBuffer.wrap(ifdOutside).putInt(14, 0x7FFFFFF0);
        segments.add(ifdOutside);
        byte[] entriesPastTheEnd = good.clone();
        ByteBuffer.wrap(entriesPastTheEnd).putShort(18, (short) 0xFFFF).putShort(32, (short) 275);
        segments.add(entriesPastTheEnd);
        for (int cut = 4; cut < 42; cut++) { // up to the orientation's value
            byte[] truncated = Arrays.copyOf(good, cut);
            ByteBuffer.wrap(truncated).putShort(2, (short) (cut - 2));
            segments.add(truncated);
        }
        for (byte[] segment : segments)
            Assert.assertArrayEquals(expected, thumbnailer.generateThumbnail(withSegment(plain, segment)).get().data);
    }

    @Test
    public void noiseInTheExifNeverThrows() throws IOException {
        byte[] plain = jpeg(stored(6)), good = exif(ByteOrder.BIG_ENDIAN, 3, 1, 6);
        Random random = new Random(42);
        for (int i = 0; i < 100_000; i++) {
            byte[] noise = good.clone();
            for (int j = 0; j < 4; j++) // anywhere after the marker, its length too
                noise[2 + random.nextInt(noise.length - 2)] = (byte) random.nextInt();
            int orientation = JavaImageThumbnailer.exifOrientation(withSegment(plain, noise));
            Assert.assertTrue(orientation >= 1 && orientation <= 8);
        }
    }
}
