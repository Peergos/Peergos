package javax.imageio;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/*
*  Dummy implementation - does nothing
* */
public class ImageIO {

    public static BufferedImage read(InputStream input) throws IOException {
        return null;
    }

    public static boolean write(RenderedImage im,
                                String formatName,
                                OutputStream output) throws IOException {
        return false;
    }
}
