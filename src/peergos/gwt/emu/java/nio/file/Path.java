package java.nio.file;

import java.io.File;
import java.util.*;

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
            throw new IllegalArgumentException("Illegal path");
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
        int index = pathString.lastIndexOf(SEPARATOR);
        if (index == -1) {
            return new Path(pathString);
        } else {
            if(index == pathString.length() - 1) {
                return null;
            } else {
                return new Path(pathString.substring(index + 1));
            }
        }
    }

    public Path resolve(String other) {
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
        throw new IllegalArgumentException("Not implemented!");
    }

    public Path getName(int index) {
        throw new IllegalArgumentException("Not implemented!");
    }

    public int getNameCount() {
        throw new IllegalArgumentException("Not implemented!");
    }

    public Path subpath(int from, int to) {
        throw new IllegalArgumentException("Not implemented!");
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
