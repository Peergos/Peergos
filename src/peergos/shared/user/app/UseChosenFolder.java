package peergos.shared.user.app;

import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Access to folders the user has chosen for an app. A grant is named by an opaque id and every path is relative to
 * the granted folder.
 */
public interface UseChosenFolder {

    CompletableFuture<List<GrantInfo>> listGrants();

    CompletableFuture<Optional<FileWrapper>> getGranted(String grantId, String relativePath);

    CompletableFuture<List<String>> dirGranted(String grantId, String relativePath);

    CompletableFuture<byte[]> readGranted(String grantId, String relativePath);

    CompletableFuture<Integer> existsGranted(String grantId, String relativePath);

    CompletableFuture<Boolean> writeGranted(String grantId, String relativePath, byte[] data);

    CompletableFuture<Boolean> appendGranted(String grantId, String relativePath, byte[] data);

    CompletableFuture<Boolean> deleteGranted(String grantId, String relativePath);

    CompletableFuture<Boolean> mkdirGranted(String grantId, String relativePath);
}
