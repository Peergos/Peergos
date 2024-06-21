package peergos.shared.user;

import jsinterop.annotations.JsType;

import java.util.*;
@JsType
public class FileSharedWithState {
    public static final FileSharedWithState EMPTY = new FileSharedWithState(Collections.emptySet(),
            Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
    public final Set<String> readAccess, writeAccess;
    public final Set<LinkProperties> readLinks, writelinks;

    public FileSharedWithState(Set<String> readAccess, Set<String> writeAccess, Set<LinkProperties> readLinks, Set<LinkProperties> writelinks) {
        this.readAccess = readAccess;
        this.writeAccess = writeAccess;
        this.readLinks = readLinks;
        this.writelinks = writelinks;
    }

    public Set<String> get(SharedWithCache.Access type) {
        if (type == SharedWithCache.Access.READ)
            return readAccess;
        return writeAccess;
    }
}
