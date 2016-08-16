package javax.imageio;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ImageIO {

	public static BufferedImage read(InputStream input) throws IOException {
		return null;
	}
	public static boolean write(RenderedImage im, String formatName, OutputStream output) throws IOException {
		return true;
	}
}
