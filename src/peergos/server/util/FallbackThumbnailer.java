package peergos.server.util;

import peergos.shared.user.fs.*;

import java.util.*;

/** Tries one thumbnailer, then the other. */
public class FallbackThumbnailer implements ThumbnailGenerator.Generator {

    private final ThumbnailGenerator.Generator first, second;

    public FallbackThumbnailer(ThumbnailGenerator.Generator first, ThumbnailGenerator.Generator second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public Optional<Thumbnail> generateThumbnail(byte[] data) {
        Optional<Thumbnail> thumbnail = first.generateThumbnail(data);
        return thumbnail.isPresent() ? thumbnail : second.generateThumbnail(data);
    }
}
