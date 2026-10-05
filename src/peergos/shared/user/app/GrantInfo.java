package peergos.shared.user.app;

import jsinterop.annotations.*;

@JsType
public class GrantInfo {
    public final String grantId;
    public final String path;
    public final boolean write;
    public final boolean persist;
    public final double granted;
    public final boolean stale;

    @JsIgnore
    public GrantInfo(String grantId, String path, boolean write, boolean persist, long granted, boolean stale) {
        this.grantId = grantId;
        this.path = path;
        this.write = write;
        this.persist = persist;
        this.granted = granted;
        this.stale = stale;
    }
}
