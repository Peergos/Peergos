package peergos.shared.user;

import java.util.*;

public class FileSharedWithState {
    public static final FileSharedWithState EMPTY = new FileSharedWithState(Collections.emptySet(), Collections.emptySet());
    public final Set<String> readAccess, writeAccess;

    public FileSharedWithState(Set<String> readAccess, Set<String> writeAccess) {
        this.readAccess = readAccess;
        this.writeAccess = writeAccess;
    }

    public Set<String> get(SharedWithCache.Access type) {
        if (type == SharedWithCache.Access.READ)
            return readAccess;
        return writeAccess;
    }
}
