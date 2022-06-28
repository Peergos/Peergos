package peergos.client;

import jsinterop.annotations.JsMethod;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PathUtils {


    @JsMethod
    public static Path getFileName(Path another) {
        return another.getFileName();
    }

    @JsMethod
    public static Path subpath(Path another, int from, int to) {
        return another.subpath(from, to);
    }

    @JsMethod
    public static int getNameCount(Path another) {
        return another.getNameCount();
    }

    @JsMethod
    public static Path getParent(Path another) {
        return another.getParent();
    }

    @JsMethod
    public static Path directoryToPath(String[] parts) {
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("Invalid params");
        }else if (parts.length == 1) {
            return Paths.get(parts[0]);
        } else {
            List<String> pathFragments = Stream.of(parts).skip(1).collect(Collectors.toList());
            String[] remainder = pathFragments.toArray(new String[1]);
            return Paths.get(parts[0], remainder);
        }
    }

    @JsMethod
    public static Path toPath(String[] parts, String filename) {
        if (parts == null || parts.length == 0 || filename == null) {
            throw new IllegalArgumentException("Invalid params");
        }else if (parts.length == 1) {
            return Paths.get(parts[0], filename);
        } else {
            List<String> pathFragments = Stream.of(parts).skip(1).collect(Collectors.toList());
            pathFragments.add(filename);
            String[] remainder = pathFragments.toArray(new String[1]);
            return Paths.get(parts[0], remainder);
        }
    }

}
