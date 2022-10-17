package peergos.server.user;

import peergos.server.util.Logging;
import peergos.shared.user.fs.*;

import javax.imageio.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import java.util.logging.*;

public class JavaImageThumbnailer implements ThumbnailGenerator.Generator {
    private final static int THUMBNAIL_SIZE = 400;

    public Optional<Thumbnail> generateThumbnail(byte[] imageBlob) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBlob));
            BufferedImage thumbnailImage = new BufferedImage(THUMBNAIL_SIZE, THUMBNAIL_SIZE, image.getType());
            Graphics2D g = thumbnailImage.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnailImage, "JPG", baos);
            baos.close();
            return Optional.of(new Thumbnail("image/jpeg", baos.toByteArray()));
        } catch (IOException ioe) {
            Logging.LOG().log(Level.WARNING, ioe.getMessage(), ioe);
        }
        return Optional.empty();
    }
}
