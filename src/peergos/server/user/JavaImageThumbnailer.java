package peergos.server.user;

import peergos.server.util.Logging;
import peergos.shared.cbor.CborObject;
import peergos.shared.user.fs.*;

import javax.imageio.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
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
}
