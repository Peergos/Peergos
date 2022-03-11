package java.nio.file;

import java.io.File;
import java.util.*;
import java.util.stream.*;

public class Path {

    private static final String SEPARATOR = "/";

    private final String pathString;

    protected Path (String pathString) {
        this.pathString = pathString;
    }

    public String toString()
    {
        return pathString;
    }

    public Path getParent() {

        int index = pathString.lastIndexOf(SEPARATOR);
        if (index == -1) {
            return null;
        } else if (index == 0) {
            String name = pathString.substring(index);
            if(name.equals(SEPARATOR)) {
                return null;
            }
            return new Path(SEPARATOR);
        } else {
            return new Path(pathString.substring(0, index));
        }
    }

    public Path getFileName() {
        int idx = pathString.lastIndexOf(SEPARATOR);
        if (idx == -1) {
            return new Path(pathString);
        }
        String filename = pathString.substring(idx+1);
        return new Path(filename);
    }

    public Path resolve(String other) {
        if(other.startsWith(SEPARATOR)) {
            return new Path(other);
        }
        if (pathString.endsWith(SEPARATOR))
            return new Path(pathString + other);
        return new Path(pathString + "/" + other);
    }

    public Path resolve(Path other) {
        return resolve(other.pathString);
    }

    public boolean isAbsolute() {
        return pathString.startsWith("/");
    }

    public boolean startsWith(Path other) {
        return pathString.startsWith(other.pathString);
    }

    public File toFile() {
        return new File(toString());
    }

    public Path getName(int index) {
        if (index < 0) {
            throw new IllegalArgumentException();
        }
        String withoutLeadingSlash = pathString.startsWith(SEPARATOR) ? pathString.substring(1)
                : pathString;
        String[] parts = withoutLeadingSlash.split(SEPARATOR);
        if (index >= parts.length) {
            throw new IllegalArgumentException();
        }
        return new Path(parts[index]);
    }

    public int getNameCount() {
        if (pathString.length() == 0) {
            return 1;
        }
        String withoutLeadingSlash = pathString.startsWith(SEPARATOR) ? pathString.substring(1)
                : pathString;
        return 1 + withoutLeadingSlash.length() - withoutLeadingSlash.replace(SEPARATOR, "").length();
    }

    public Path subpath(int from, int to) {
        if (from < 0) {
            throw new IllegalArgumentException();
        }
        String withoutLeadingSlash = pathString.startsWith(SEPARATOR) ? pathString.substring(1)
                : pathString;
        String[] parts = withoutLeadingSlash.split(SEPARATOR);
        if (to > parts.length) {
            throw new IllegalArgumentException();
        }
        StringBuilder sb = new StringBuilder();
        for(int i = from; i < to; i++) {
            sb.append(parts[i]);
            if(i < to -1){
                sb.append(SEPARATOR);
            }
        }
        return new Path(sb.toString());
    }

    public static Path of(String name) {
        return new Path(name);
    }

    public static Path of(String name, String... rest) {
        if (rest.length == 0)
            return new Path(name);
        return new Path(name + "/" + Arrays.stream(rest).collect(Collectors.joining("/")));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Path path = (Path) o;
        return Objects.equals(pathString, path.pathString);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pathString);
    }
}
