package peergos.server.tests.simulation;

import peergos.shared.user.fs.FileProperties;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

public class NativeFileSystemImpl implements FileSystem {

    private final Path root;
    private final String user;
    private final AccessControl accessControl = new AccessControl.MemoryImpl();

    public NativeFileSystemImpl(Path root, String user) {
        this.root = root;
        this.user = user;
    }

    @Override
    public String user() {
        return user;
    }

    private void ensureCan(Path path, Permission permission) {
        ensureCan(path, permission, user());
    }

    private void ensureCan(Path path, Permission permission, String user) {
        if (! Files.exists(path))
            throw new IllegalStateException("Cannot read "+ path +" : does not exist");

        if (! accessControl.can(path, user, permission))
            throw new IllegalStateException("User " + user() +" not permitted to "+ permission + " " + path);
    }

    @Override
    public byte[] read(Path path) {
        Path nativePath = virtualToNative(path);
        ensureCan(path, Permission.READ);

        try {
            return Files.readAllBytes(nativePath);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    @Override
    public void write(Path path, byte[] data) {

        Path nativePath = virtualToNative(path);
        ensureCan(path, Permission.WRITE);

        try {
            Files.write(nativePath, data);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    @Override
    public void delete(Path path) {
        Path nativePath = virtualToNative(path);
        ensureCan(path, Permission.WRITE);
        try {
            Files.delete(nativePath);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    private boolean isOwner(Path path) {
        return user().equals(accessControl.getOwner(path));
    }

    @Override
    public void grant(Path path, String user, FileSystem.Permission permission) {
        ensureCan(path, permission, user);
        accessControl.add(path, user, permission);
    }

    @Override
    public void revoke(Path path, String user, FileSystem.Permission permission) {
        ensureCan(path, permission, user);
        accessControl.remove(path, user, permission);
    }

    @Override
    public Stat stat(Path path) {
        return new Stat() {
            @Override
            public String user() {
                return user;
            }

            @Override
            public FileProperties fileProperties() {
                File file = virtualToNative(path).toFile();

                long length = file.length();

                file.lastModified();
                if (Integer.MAX_VALUE < length)
                    throw new IllegalStateException("Large files not supported");
                int sizeLo = (int) length;
                int sizeHi = 0;

                LocalDateTime lastModified = LocalDateTime.ofInstant(Instant.ofEpochSecond(file.lastModified() / 1000),
                        ZoneOffset.systemDefault());

                // These things are a bit awkward, not supporting them for now
                Optional<byte[]> thumbnail = Optional.empty();
                boolean isHidden = false;
                String mimeType="NOT_SUPPORTED";

                return new FileProperties(file.getName(), mimeType, sizeHi, sizeLo, lastModified, isHidden, thumbnail);

            }

            @Override
            public boolean isReadable() {
                return accessControl.can(path, user(), Permission.READ);
            }

            @Override
            public boolean isWritable() {
                return accessControl.can(path, user(), Permission.WRITE);
            }
        };
    }

    private Path virtualToNative(Path path) {
        Path relativePath = Paths.get("/").relativize(path);
        return Paths.get(root.toString(), relativePath.toString());
    }



    public static void main(String[] args) {
        System.out.println("HELO");

        Path p1 = Paths.get("/something/else");
        Path p2 = Paths.get("/another/thing");

        Path p3 = Paths.get("/").relativize(p2);
        Path p4 = Paths.get(p1.toString(), p3.toString());

        System.out.println(p4);

    }
}
