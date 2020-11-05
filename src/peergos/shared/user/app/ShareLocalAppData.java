package peergos.shared.user.app;

import java.nio.file.*;
import java.util.concurrent.*;

/**
 * These are available to apps that have been granted the SHARE_LOCAL_APP_DATA permission
 */
public interface ShareLocalAppData {

    CompletableFuture<Boolean> shareInternal(Path relativePath);
}
