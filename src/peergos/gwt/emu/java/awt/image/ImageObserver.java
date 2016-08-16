package java.awt.image;

import java.awt.Image;

public class ImageObserver {

	public boolean imageUpdate(Image img, int infoflags, int x, int y, int width, int height)
	{
		return true;
	}
}
