package peergos.server.util;

import org.peergos.thumbnailer.VideoThumbnailer;
import peergos.shared.user.fs.Thumbnail;
import peergos.shared.user.fs.ThumbnailGenerator;

import java.io.*;
import java.nio.file.*;
import java.util.Optional;

public class FFmpegThumbnailer implements ThumbnailGenerator.Generator, ThumbnailGenerator.VideoGenerator {

    private static final int SIZE = 400;

    /** Returns a thumbnailer if the native lib loaded successfully, otherwise empty. */
    public static Optional<FFmpegThumbnailer> create() {
        return VideoThumbnailer.isAvailable() ? Optional.of(new FFmpegThumbnailer()) : Optional.empty();
    }

    private static String extensionFor(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8)
            return ".jpg";
        if (data.length >= 4 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F')
            return ".webp";
        if (data.length >= 8 && data[0] == (byte)0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G')
            return ".png";
        if (data.length >= 4 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F')
            return ".gif";
        if (data.length >= 4 && (data[0] & 0xFF) == 0x49 && data[1] == 0x49 && data[2] == 0x2A)
            return ".tiff";
        if (data.length >= 4 && (data[0] & 0xFF) == 0x4D && data[1] == 0x4D && data[2] == 0x00)
            return ".tiff";
        if (data.length >= 12 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p'
                && (   (data[8] == 'a' && data[9] == 'v' && data[10] == 'i' && data[11] == 'f')
                || (data[8] == 'a' && data[9] == 'v' && data[10] == 'i' && data[11] == 's')))
            return ".avif";
        return ".img";
    }

    @Override
    public Optional<Thumbnail> generateThumbnail(byte[] data) {
        Path tmp = null;
        try {
            // ffmpeg relies on file extension
            tmp = Files.createTempFile("ffmpeg-thumb-", extensionFor(data));
            Files.write(tmp, data);
            return VideoThumbnailer.generateImageWebP(tmp.toFile(), SIZE)
                    .map(bytes -> new Thumbnail("image/webp", bytes));
        } catch (IOException e) {
            return Optional.empty();
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public Optional<Thumbnail> generateVideoThumbnail(File video) {
        return VideoThumbnailer.generateWebP(video, SIZE)
                .map(bytes -> new Thumbnail("image/webp", bytes));
    }
}
