package peergos.shared.user.fs;

import java.util.*;

public class ThumbnailGenerator {

    public interface Generator {
        Optional<Thumbnail> generateThumbnail(byte[] data);
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
}
