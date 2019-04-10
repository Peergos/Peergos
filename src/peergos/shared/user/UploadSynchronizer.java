package peergos.shared.user;

import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.AsyncLock;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class UploadSynchronizer {

    private final Map<String, AsyncLock<FileWrapper>> pending = new ConcurrentHashMap<>();

    public UploadSynchronizer() {
    }

    private CompletableFuture<FileWrapper> getUpdatedDirectory(FileWrapper directory, UserContext context) {
        return directory.getPath(context.network)
                .thenCompose(path -> context.getByPath(path).thenApply(opt -> {
                    if(! opt.isPresent())
                        throw new IllegalStateException("Directory not found: " + path);
                    return opt.get();
                }));
    }

    public CompletableFuture<FileWrapper> applyUpdate(String directoryPath, FileWrapper directory, UserContext context,
                                                              Function<FileWrapper, CompletableFuture<FileWrapper>> updater) {
        return pending.computeIfAbsent(directoryPath, p -> new AsyncLock<>(getUpdatedDirectory(directory, context)))
                .runWithLock(current -> updater.apply(current), () -> getUpdatedDirectory(directory, context));
    }

}
