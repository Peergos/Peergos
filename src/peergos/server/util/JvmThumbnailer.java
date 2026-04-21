package peergos.server.util;

import peergos.server.user.JavaImageThumbnailer;
import peergos.shared.user.fs.ThumbnailGenerator;

public class JvmThumbnailer {

    public static void initJava() {
        ThumbnailGenerator.setInstance(new JavaImageThumbnailer());
    }
}
