package java.awt;

/*
*  Dummy implementation - does nothing
* */
public class Graphics2D {

    public void setComposite(Composite comp) {}

    public void setRenderingHint(RenderingHints.Key hintKey, Object hintValue) {}

    public boolean drawImage(Image img, int x, int y,
                                      int width, int height,
                                      ImageObserver observer) { return false; }

    public void dispose() {}
}
