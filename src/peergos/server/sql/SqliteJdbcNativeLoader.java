package peergos.server.sql;

import org.sqlite.util.LibraryLoaderUtil;

import java.io.*;
import java.nio.file.*;
import java.util.Optional;

public class SqliteJdbcNativeLoader {

    private static volatile boolean loaded = false;

    public static synchronized void load(Optional<Path> providedPath) {
        if (loaded || providedPath.isEmpty())
            return;

        Path target = providedPath.get();
        String resourcePath = LibraryLoaderUtil.getNativeLibResourcePath();
        String libName = LibraryLoaderUtil.getNativeLibName();
        InputStream resource = SqliteJdbcNativeLoader.class
                .getResourceAsStream("/" + resourcePath + "/" + libName);
        if (resource == null)
            return; // no bundled native lib for this platform; let sqlite-jdbc handle it normally

        try {
            byte[] data = resource.readAllBytes();
            try {
                Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                if (!Files.exists(target))
                    throw new RuntimeException("Cannot write native SQLite library to " + target, e);
                // read-only but file already exists: use the existing file
            }
            System.setProperty("org.sqlite.lib.path", target.getParent().toAbsolutePath().toString());
            System.setProperty("org.sqlite.lib.name", target.getFileName().toString());
            loaded = true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to set up native SQLite library at " + target, e);
        }
    }
}
