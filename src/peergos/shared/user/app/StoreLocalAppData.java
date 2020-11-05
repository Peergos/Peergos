package peergos.shared.user.app;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * These are available to apps that have been granted the STORE_LOCAL_APP_DATA permission
 */
public interface StoreLocalAppData {

    CompletableFuture<List<String>> dirInternal(Path relativePath);

    CompletableFuture<byte[]> readInternal(Path relativePath);

    CompletableFuture<Boolean> writeInternal(Path relativePath, byte[] data);

    CompletableFuture<Boolean> deleteInternal(Path relativePath);
}
