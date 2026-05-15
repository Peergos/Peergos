package peergos.server.util;

import peergos.server.user.JavaImageThumbnailer;
import peergos.shared.user.fs.ThumbnailGenerator;

public class JvmThumbnailer {

    public static void initJava() {
        try {
            FFmpegThumbnailer.create().ifPresentOrElse(
                    ffmpeg -> {
                        ThumbnailGenerator.setInstance(ffmpeg);
                        ThumbnailGenerator.setVideoInstance(ffmpeg);
                    },
                    () -> ThumbnailGenerator.setInstance(new JavaImageThumbnailer())
            );
        } catch (Throwable e) {
            System.err.println("Unable to load native thumbnailer: " + e.getMessage());
            ThumbnailGenerator.setInstance(new JavaImageThumbnailer());
        }
    }
}
