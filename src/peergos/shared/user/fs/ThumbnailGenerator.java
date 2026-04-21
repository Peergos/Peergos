package peergos.shared.user.fs;

import java.util.*;
import java.io.*;

public class ThumbnailGenerator {

    public interface Generator {
        Optional<Thumbnail> generateThumbnail(byte[] data);
    }

    public interface VideoGenerator {
        Optional<Thumbnail> generateVideoThumbnail(File video);
    }

    private static Generator instance;

    public static synchronized void setInstance(Generator instance) {
        ThumbnailGenerator.instance = instance;
    }

    public static synchronized Generator get() {
        if (instance == null)
            throw new IllegalStateException("Thumbnail generator hasn't been set!");
        return instance;
    }

    static class NoopVideoThumbnailer implements VideoGenerator {
        @Override
        public Optional<Thumbnail> generateVideoThumbnail(File video) {
            return Optional.empty();
        }
    }

    private static VideoGenerator videoInstance = new NoopVideoThumbnailer();

    public static synchronized void setVideoInstance(VideoGenerator instance) {
        ThumbnailGenerator.videoInstance = instance;
    }

    public static synchronized VideoGenerator getVideo() {
        if (videoInstance == null)
            throw new IllegalStateException("Video thumbnail generator hasn't been set!");
        return videoInstance;
    }
}
