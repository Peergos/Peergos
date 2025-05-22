package peergos.server;

import java.util.concurrent.CompletableFuture;

public interface HostDirChooser {
    CompletableFuture<String> chooseDir();
}
