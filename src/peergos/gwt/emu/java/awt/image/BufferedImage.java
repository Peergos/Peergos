package java.awt.image;

import java.awt.Graphics2D;

public class BufferedImage extends java.awt.Image implements WritableRenderedImage {

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
}
