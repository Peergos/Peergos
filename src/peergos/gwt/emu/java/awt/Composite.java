package java.awt;

import java.awt.image.ColorModel;

public interface Composite {

	public CompositeContext createContext(ColorModel srcColorModel, ColorModel dstColorModel, RenderingHints hints);
}
