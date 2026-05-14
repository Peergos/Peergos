package peergos.server.util;

import peergos.server.user.JavaImageThumbnailer;
import peergos.shared.user.fs.ThumbnailGenerator;

public class JvmThumbnailer {

    public static void initJava() {
        FFmpegThumbnailer.create().ifPresentOrElse(
                ffmpeg -> {
                    ThumbnailGenerator.setInstance(ffmpeg);
                    ThumbnailGenerator.setVideoInstance(ffmpeg);
                },
                () -> ThumbnailGenerator.setInstance(new JavaImageThumbnailer())
        );
    }
}
