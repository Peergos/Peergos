package peergos.server.tests.simulation;

import peergos.shared.user.fs.FileAccess;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.FileWrapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class NativeFileSystemImpl extends FileSystem {

    private final Path root;
    private final String user;

    public NativeFileSystemImpl(Path root, String user) {
        this.root = root;
        this.user = user;
    }

    @Override
    public String user() {
        return user;
    }

    @Override
    public byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    @Override
    public void write(Path path, byte[] data) {
        try {
            Files.write(path, data);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    @Override
    public void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    @Override
    public void grant(List<String> users, Permission permission) {

    }

    @Override
    public void revoke(List<String> users, Permission permission) {

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
                File file = path.toFile();
                long length = file.length();
                file.lastModified();
                if (Integer.MAX_VALUE < length)
                    throw new IllegalStateException("Large files not supported");
                int sizeLo = (int) length;
                int sizeHi = 0;

                return new FileProperties(path.getFileName().toString(), null, length,
                        LocalDateTime.ofEpochSecond());
            }

            @Override
            public boolean isReadable() {
                return false;
            }

            @Override
            public boolean isWritable() {
                return false;
            }
        };
    }
}
