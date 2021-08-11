package peergos.shared.user.fs;

import jsinterop.annotations.*;

public class Thumbnail {
    public final String mimeType;
    public final byte[] data;

    @JsConstructor
    public Thumbnail(String mimeType, byte[] data) {
        if (data.length > 100*1024)
            throw new IllegalStateException("Image thumbnails must be < 100 KiB!");
        this.mimeType = mimeType;
        this.data = data;
    }
}
