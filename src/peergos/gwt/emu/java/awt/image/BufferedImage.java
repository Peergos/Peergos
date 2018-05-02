package java.awt.image;

import java.awt.*;

/*
*  Dummy implementation - does nothing
* */
public class BufferedImage extends Image implements RenderedImage {

    public BufferedImage(int width,
                         int height,
                         int imageType) {

    }

    public int getType() {
        return -1;
    }

    public Graphics2D createGraphics() {
        return null;
    }

    public int getWidth() {
        return -1;
    }

    public int getHeight() {
        return -1;
    }

    public int getRGB(int x, int y) {
        return -1;
    }
}
