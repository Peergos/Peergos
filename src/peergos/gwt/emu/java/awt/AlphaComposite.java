package java.awt;

import java.awt.image.ColorModel;

public class AlphaComposite implements Composite{

	public static final int     SRC             = 2;
	public static final AlphaComposite Src      = new AlphaComposite(SRC);
	
    private final float alpha;
    private final int rule;

    private AlphaComposite(int rule) {
        this.rule = rule;
        this.alpha = 1.0f;
    }

	@Override
	public CompositeContext createContext(ColorModel srcColorModel, ColorModel dstColorModel, RenderingHints hints) {
		return null;
	}
    
}
