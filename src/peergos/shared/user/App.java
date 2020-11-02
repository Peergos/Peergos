package peergos.shared.user;

import jsinterop.annotations.JsMethod;
import peergos.shared.cbor.CborObject;
import peergos.shared.social.SharedItem;
import peergos.shared.social.SocialFeed;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Futures;
import peergos.shared.util.Serialize;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class App {
    public static final String SHARED_WITH_US_CACHE_FILENAME = "sharedWithUs.cbor";
    public static final String APPS_DIR_NAME = ".apps";
    public static final String DATA_DIR_NAME = "data";

    private final UserContext ctx;
    private final Path appDataDirectory;
    private List<SharedItem> allSharedEvents = new ArrayList<>();
    private App(UserContext ctx, Path appDataDirectory) {
        this.ctx = ctx;
        this.appDataDirectory = appDataDirectory;
    }

    @JsMethod
    public static CompletableFuture<App> init(UserContext ctx, String appName, String fileExtension) {
        App app = new App(ctx, Paths.get(ctx.username, APPS_DIR_NAME, appName, DATA_DIR_NAME));
        Path appPath = Paths.get(APPS_DIR_NAME, appName, ctx.username);
        Path basePath = Paths.get(ctx.username, APPS_DIR_NAME, appName);
        Path cacheFilePath = basePath.resolve(SHARED_WITH_US_CACHE_FILENAME);
        return ctx.getByPath("/" + ctx.username).thenCompose(root -> root.get().getOrMkdirs(appPath, ctx.network, true, ctx.crypto))
                .thenCompose(appDir -> ctx.getByPath(basePath).thenCompose(fw -> fw.get().hasChild(SHARED_WITH_US_CACHE_FILENAME, ctx.crypto.hasher, ctx.network).thenCompose(exists ->
                        exists ? Futures.of(true) : app.writeFileContents(cacheFilePath, SharedItemCache.empty().toCbor().toByteArray()))
                        .thenCompose(res -> app.getSharedItems(appName, item -> item.path.endsWith(fileExtension))
                                .thenApply(sharedEvents -> {
                                    app.allSharedEvents = sharedEvents;
                                    return app;
                                }))
                ));
    }

    private Path fullPath(Path path) {
        String pathAsString = path.toString().trim();
        Path relativePath = pathAsString.startsWith("/") ? Paths.get(pathAsString.substring(1)) : Paths.get(pathAsString);
        return appDataDirectory.resolve(relativePath);
    }

    private CompletableFuture<Boolean> writeFileContents(Path path, byte[] data) {
        Path pathWithoutUsername = Paths.get(Stream.of(path.toString().split("/")).skip(1).collect(Collectors.joining("/")));
        return ctx.getByPath(ctx.username).thenCompose(userRoot -> userRoot.get().getOrMkdirs(pathWithoutUsername.getParent(), ctx.network, true, ctx.crypto)
                .thenCompose(dir -> dir.uploadOrReplaceFile(path.getFileName().toString(), AsyncReader.build(data),
                        data.length, ctx.network, ctx.crypto, x -> {
                        }, ctx.crypto.random.randomBytes(32))
                        .thenApply(fw -> true)
                ));
    }

    @JsMethod
    public CompletableFuture<byte[]> readInternal(Path relativePath) {
        return readFileContents(fullPath(relativePath));
    }

    private CompletableFuture<byte[]> readFileContents(Path path) {
        return ctx.getByPath(path).thenCompose(optFile -> {
            if(optFile.isEmpty()) {
                throw new IllegalStateException("File not found:" + path.toString());
            }
            long len = optFile.get().getSize();
            return optFile.get().getInputStream(ctx.network, ctx.crypto, len, l-> {})
                    .thenCompose(is -> Serialize.readFully(is, len)
                            .thenApply(bytes -> bytes));
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> writeInternal(Path relativePath, byte[] data) {
        return writeFileContents(fullPath(relativePath), data);
    }

    @JsMethod
    public CompletableFuture<Boolean> deleteInternal(Path relativePath) {
        Path path = fullPath(relativePath);
        return ctx.getByPath(path.getParent()).thenCompose(dirOpt -> {
            if(dirOpt.isEmpty()) {
                throw new IllegalStateException("File not found:" + path.toString());
            }
            FileWrapper dir = dirOpt.get();
            String filename = path.getFileName().toString();
            Path pathToFile = path.resolve(filename);
            return dir.getChild(filename, ctx.crypto.hasher, ctx.network).thenCompose(file ->
                    file.get().remove(dir, pathToFile, ctx).thenApply(fw -> true));
        });
    }

    @JsMethod
    public CompletableFuture<String> createSecretLinkInternal(Path relativePath) {
        Path path = fullPath(relativePath);
        return ctx.getByPath(path).thenCompose(fileOpt -> {
            if (fileOpt.isPresent()) {
                return Futures.of(fileOpt.get().toLink());
            } else {
                return Futures.errored(new IllegalStateException("Unable to find Event directory"));
            }
        });
    }

    @JsMethod
    public List<SharedItem> filterSharedItems(Function<SharedItem, Boolean> filter) {
        return allSharedEvents.stream().filter(item -> filter.apply(item)).collect(Collectors.toList());
    }

    private CompletableFuture<SharedItemCache> readSharedItemCacheFile(Path path) {
        return readFileContents(path).thenApply(bytes ->  SharedItemCache.fromCbor(CborObject.fromByteArray(bytes)));
    }

    private CompletableFuture<List<SharedItem>> getSharedItems(String appName, Function<SharedItem, Boolean> sharedItemFilter) {
        Function<SharedItem, Boolean> appFilter  = item -> {
            Path path = Paths.get(item.path);
            return path.getNameCount() >=4 && path.getName(1).toString().equals(APPS_DIR_NAME) && path.getName(2).toString().equals(appName);
        };
        Function<List<SharedItem>, List<SharedItem>> sharedItemsFilter = items ->
                items.stream().filter(f -> appFilter.apply(f)).filter(f -> sharedItemFilter.apply(f)).collect(Collectors.toList());
        Path path = Paths.get(ctx.username, APPS_DIR_NAME, appName);
        Path cacheFilePath = path.resolve(SHARED_WITH_US_CACHE_FILENAME);
        int pageSize = 100;
        return readSharedItemCacheFile(cacheFilePath).thenCompose(cachedItems -> {
                    int socialFeedIndex = cachedItems.getReadIndex();
                    return ctx.getSocialFeed().thenCompose(feed -> feed.update().thenCompose( updatedFeed -> {
                        if (socialFeedIndex < updatedFeed.getLastSeenIndex() || updatedFeed.hasUnseen() || socialFeedIndex == 0) {
                            CompletableFuture<List<SharedItem>> future = Futures.incomplete();
                            return retrieveUnSeenSharedItems(updatedFeed, socialFeedIndex, pageSize, new ArrayList<>(), sharedItemsFilter, future)
                                    .thenCompose(newItems -> {
                                        List<SharedItem> combinedItems = new ArrayList<>(cachedItems.getItems());
                                        combinedItems.addAll(newItems);
                                        SharedItemCache updatedSharedCachedItems = new SharedItemCache(combinedItems, updatedFeed.getLastSeenIndex());
                                        return writeFileContents(cacheFilePath, updatedSharedCachedItems.serialize())
                                                .thenCompose(res -> ctx.getFiles(combinedItems).thenApply(i -> i.stream().map(e -> e.left).collect(Collectors.toList())));
                                    });
                        } else {
                            return ctx.getFiles(cachedItems.getItems()).thenApply(i -> i.stream().map(e -> e.left).collect(Collectors.toList()));
                        }
                    }));
                }
        );
    }

    private CompletableFuture<List<SharedItem>> retrieveUnSeenSharedItems(SocialFeed feed, int lastSeenIndex, int pageSize,
                                                                          List<SharedItem> results,
                                                                          Function<List<SharedItem>, List<SharedItem>> filter,
                                                                          CompletableFuture<List<SharedItem>> future) {
        if (! feed.hasUnseen() ) {
            future.complete(results);
            return future;
        } else {
            return feed.getShared(lastSeenIndex, lastSeenIndex + pageSize, ctx.crypto, ctx.network)
                    .thenCompose(sharedItems -> {
                        int newIndex = lastSeenIndex + sharedItems.size();
                        return feed.setLastSeenIndex(newIndex).thenCompose(res -> {
                            results.addAll(filter.apply(sharedItems));
                            return retrieveUnSeenSharedItems(feed, newIndex, pageSize, results, filter, future);
                        });
                    });
        }
    }
}

