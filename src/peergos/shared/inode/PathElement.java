package peergos.shared.inode;

import peergos.shared.user.fs.*;

import java.util.*;

public class PathElement {
    public final String name;

    public PathElement(String name) {
        if (name.contains("/") || name.length() > FileProperties.MAX_FILE_NAME_SIZE)
            throw new IllegalStateException("Invalid path element");
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathElement that = (PathElement) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    public String toString() {
        return name;
    }
}
