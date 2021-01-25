package peergos.shared.user.app;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * These are available to apps that have been granted the STORE_LOCAL_APP_DATA permission
 */
public interface StoreLocalAppData {

    CompletableFuture<List<String>> dirInternal(Path relativePath, String username);

    CompletableFuture<byte[]> readInternal(Path relativePath, String username);

    CompletableFuture<Boolean> writeInternal(Path relativePath, byte[] data, String username);

    CompletableFuture<Boolean> deleteInternal(Path relativePath, String username);
}
