package peergos.shared.user;

import jsinterop.annotations.JsType;

import java.util.*;
@JsType
public class FileSharedWithState {
    public static final FileSharedWithState EMPTY = new FileSharedWithState(Collections.emptySet(),
            Collections.emptySet(), Collections.emptySet());
    public final Set<String> readAccess, writeAccess;
    public final Set<LinkProperties> links;

    public FileSharedWithState(Set<String> readAccess, Set<String> writeAccess, Set<LinkProperties> links) {
        this.readAccess = readAccess;
        this.writeAccess = writeAccess;
        this.links = links;
    }

    public Set<String> get(SharedWithCache.Access type) {
        if (type == SharedWithCache.Access.READ)
            return readAccess;
        return writeAccess;
    }
}
