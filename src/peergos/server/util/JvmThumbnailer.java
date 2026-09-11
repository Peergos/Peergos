package peergos.server.util;

import peergos.server.user.JavaImageThumbnailer;
import peergos.shared.user.fs.*;

import java.nio.file.*;
import java.util.*;

public class JvmThumbnailer {

    /** Where the thumbnailer worker's complaints about files it can't read are kept. */
    public static final String WORKER_LOG = "thumbnailer.log";

    public static void initJava(Args a) {
        initJava(Optional.of(a.getPeergosDirChild(WORKER_LOG)));
    }

    public static void initJava() {
        initJava(Optional.empty());
    }

    public static void initJava(Optional<Path> workerLog) {
        JavaImageThumbnailer images = new JavaImageThumbnailer();
        try {
            ThumbnailerHost.create(workerLog).ifPresentOrElse(
                    ffmpeg -> {
                        // ffmpeg is the better thumbnailer, but an image is not worth losing when it
                        // can't parse one, or when its worker is gone
                        ThumbnailGenerator.setInstance(new FallbackThumbnailer(ffmpeg, images));
                        ThumbnailGenerator.setVideoInstance(ffmpeg);
                    },
                    () -> ThumbnailGenerator.setInstance(images)
            );
        } catch (Throwable e) {
            System.err.println("Unable to load native thumbnailer: " + e.getMessage());
            ThumbnailGenerator.setInstance(images);
        }
    }
}
