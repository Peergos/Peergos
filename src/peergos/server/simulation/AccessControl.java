package peergos.server.simulation;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface AccessControl {



    List<String> get(Path path, FileSystem.Permission permission);

    void add(Path path, String reader, FileSystem.Permission permission);

    void remove(Path path, String reader, FileSystem.Permission permission);

    void remove(Path path);

    Path getRandomSharedPath(Random random, FileSystem.Permission permission, String sharee);
    /**
     * Model owners, reader and writers
     */
    class AccessControlUnit {
        private Map<Path, List<String>> allowed = new HashMap<>();
        private Map<String, Set<Path>> allowedReversed = new HashMap<>();

        List<String> getAllowed(Path path) {
            return allowed.computeIfAbsent(path, p -> new ArrayList<>());
        }

        void addAllowed(Path path, String user) {
            getAllowed(path).add(user);
            allowedReversed.computeIfAbsent(user, s -> new HashSet<>()).add(path);
        }

        void removeAllowed(Path path, String user) {
            getAllowed(path).remove(user);
            allowedReversed.get(user).remove(path);
        }

        void remove(Path p) {
            allowed.remove(p);
            allowedReversed.forEach((k,v)  -> v.remove(p));
        }

    }

    static String getOwner(Path path) {
        /**
         * Should be "/owner/..."
         */
        try {
            return path.getName(0).toString();
        } catch (Throwable t) {
            throw t;
        }
    }

    default boolean can(Path path, String user, FileSystem.Permission permission) {

        boolean isOwner = getOwner(path).equals(user);
        if (isOwner)
            return true;
        //check for sharing of the path and all of it's parents
        Path sharePath = path;
        while (sharePath != null) {
            boolean isShared = get(sharePath, permission).contains(user);
            if (isShared)
                return true;
            sharePath = sharePath.getParent();
        }
        return false;
    }

    class MemoryImpl implements AccessControl {
        public AccessControlUnit readers = new AccessControlUnit();
        public AccessControlUnit writers = new AccessControlUnit();

        private AccessControlUnit getAcu(FileSystem.Permission permission) {
            switch (permission) {
                case READ:
                    return readers;
                case WRITE:
                    return writers;
                default:
                    throw new IllegalStateException("Unimplemented");
            }
        }

        @Override
        public List<String> get(Path path, FileSystem.Permission permission) {
            switch (permission) {
                case WRITE:
                    return writers.getAllowed(path);
                case READ:
                    return readers.getAllowed(path);
                default:
                    throw new IllegalStateException("Unimplemented");
            }
        }

        @Override
        public void add(Path path, String reader, FileSystem.Permission permission) {
            getAcu(permission).addAllowed(path, reader);
        }

        @Override
        public void remove(Path path, String reader, FileSystem.Permission permission) {
            getAcu(permission).removeAllowed(path, reader);
        }

        @Override
        public Path getRandomSharedPath(Random random, FileSystem.Permission permission, String sharee) {
            AccessControlUnit acu = getAcu(permission);
            Set<Path> sharedPaths  = acu.allowedReversed.get(sharee);
            if (sharedPaths ==  null || sharedPaths.isEmpty()) {
                throw new IllegalStateException();
            }
            int nSkip = random.nextInt(sharedPaths.size());
            return sharedPaths.stream()
                    .skip(nSkip)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException());
        }

        @Override
        public void remove(Path path) {
            getAcu(FileSystem.Permission.READ).remove(path);
            getAcu(FileSystem.Permission.WRITE).remove(path);

        }
    }
}
