package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.social.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class App {

    //Boolean indicates new file
    private static CompletableFuture<Pair<Boolean, SharedItemCacheFile>> readCacheFile(UserContext ctx, Path path) {
        return ctx.getByPath(path).thenCompose(optFile -> {
            if (!optFile.isEmpty()) {
                long len = optFile.get().getSize();
                return optFile.get().getInputStream(ctx.network, ctx.crypto, len, l -> {
                })
                        .thenCompose(is -> Serialize.readFully(is, len).thenApply(bytes ->
                                new Pair<>(false, SharedItemCacheFile.fromCbor(CborObject.fromByteArray(bytes)))));
            } else {
                return Futures.of(new Pair<>(true, new SharedItemCacheFile(Collections.emptyList())));
            }
        });
    }

    private static CompletableFuture<Boolean> saveCacheFile(UserContext ctx, Path path, SharedItemCacheFile cacheFile) {
        byte[] data = cacheFile.serialize();
        return ctx.getByPath(path.getParent()).thenCompose(fileOpt ->
                fileOpt.get().uploadOrReplaceFile(path.getFileName().toString(), AsyncReader.build(data),
                        data.length, ctx.network, ctx.crypto, x -> {
                        }, ctx.crypto.random.randomBytes(32))
                        .thenApply(fw -> true)
        );
    }

    private static CompletableFuture<PropertiesFile> readPropertiesFile(UserContext ctx, Path path) {
        return ctx.getByPath(path).thenCompose(optFile -> {
            if (!optFile.isEmpty()) {
                long len = optFile.get().getSize();
                return optFile.get().getInputStream(ctx.network, ctx.crypto, len, l -> {
                })
                        .thenCompose(is -> Serialize.readFully(is, len).thenApply(bytes ->
                                PropertiesFile.fromCbor(CborObject.fromByteArray(bytes))));
            } else {
                return Futures.of(PropertiesFile.empty());
            }
        });
    }

    private static CompletableFuture<Boolean> savePropertiesFile(UserContext ctx, Path path, PropertiesFile propsFile) {
        byte[] data = propsFile.serialize();
        return ctx.getByPath(path.getParent()).thenCompose(fileOpt ->
                fileOpt.get().uploadOrReplaceFile(path.getFileName().toString(), AsyncReader.build(data),
                        data.length, ctx.network, ctx.crypto, x -> {
                        }, ctx.crypto.random.randomBytes(32))
                        .thenApply(fw -> true)
        );
    }

    private static CompletableFuture<List<SharedItem>> getSharedItems(UserContext ctx, Path cacheFilePath, Path propsPath,
                                                                      Function<List<SharedItem>, List<SharedItem>> filter) {
        int pageSize = 100;
        final String socialFeedIndexKey = "social.feed.index";
        return readCacheFile(ctx, cacheFilePath).thenCompose(cacheFilePair ->
                readPropertiesFile(ctx, propsPath).thenCompose(props -> {
                    boolean isNewCacheFile = cacheFilePair.left;
                    List<SharedItem> items = cacheFilePair.right.getItems();
                    int socialFeedIndex = props.getIntProperty(socialFeedIndexKey, 0);
                    return ctx.getSocialFeed().thenCompose(feed -> feed.update().thenCompose(updatedFeed -> {
                        if (socialFeedIndex < updatedFeed.getLastSeenIndex() || updatedFeed.hasUnseen() || isNewCacheFile) {
                            int lastSeenIndex = isNewCacheFile ? 0 : socialFeedIndex;
                            CompletableFuture<List<SharedItem>> future = Futures.incomplete();
                            return retrieveUnSeenSharedItems(updatedFeed, lastSeenIndex, pageSize, new ArrayList<>(), filter, ctx, future)
                                    .thenCompose(newItems -> {
                                        items.addAll(newItems);
                                        props.setIntProperty(socialFeedIndexKey, updatedFeed.getLastSeenIndex());
                                        return saveCacheFile(ctx, cacheFilePath, new SharedItemCacheFile(items))
                                                .thenCompose(res -> savePropertiesFile(ctx, propsPath, props))
                                                .thenCompose(res -> ctx.getFiles(items).thenApply(i -> i.stream().map(e -> e.left).collect(Collectors.toList())));
                                    });
                        } else {
                            return ctx.getFiles(items).thenApply(i -> i.stream().map(e -> e.left).collect(Collectors.toList()));
                        }
                    }));
                })
        );
    }

    private static CompletableFuture<List<SharedItem>> retrieveUnSeenSharedItems(SocialFeed feed, int lastSeenIndex, int pageSize,
                                                                                 List<SharedItem> results,
                                                                                 Function<List<SharedItem>, List<SharedItem>> filter,
                                                                                 UserContext ctx, CompletableFuture<List<SharedItem>> future) {
        if (!feed.hasUnseen()) {
            future.complete(results);
            return future;
        } else {
            return feed.getShared(lastSeenIndex, lastSeenIndex + pageSize, ctx.crypto, ctx.network)
                    .thenCompose(sharedItems -> {
                        int newIndex = lastSeenIndex + sharedItems.size();
                        return feed.setLastSeenIndex(newIndex).thenCompose(res -> {
                            results.addAll(filter.apply(sharedItems));
                            return retrieveUnSeenSharedItems(feed, newIndex, pageSize, results, filter, ctx, future);
                        });
                    });
        }
    }

    public static class Todo {
        private UserContext ctx;
        public static final String TODO_DIR_NAME = "todo";
        @JsProperty
        public static final String TODO_FILE_EXTENSION = ".todo";
        public static final String SHARED_WITH_US_CACHE_FILENAME = "sharedWithUs.cbor";
        public static final String PROPERTIES_FILENAME = "properties.cbor";
        private static Comparator<Pair<String, String>> sortByUser = Comparator.comparing(pair -> pair.left);
        private static Comparator<Pair<String, String>> sortByTodoName = Comparator.comparing(pair -> pair.right);
        private static Comparator<Pair<String, String>> todoListSorter = sortByUser.thenComparing(sortByTodoName);

        private static final Function<List<SharedItem>, List<SharedItem>> sharedItemsFilter = items ->
                items.stream().filter(f -> {
                    try {
                        Path path = Paths.get(f.path);
                        Path parent = path.getParent();
                        Path grandParent = parent.getParent();
                        return f.path.endsWith(TODO_FILE_EXTENSION) && path.getNameCount() == 4
                                && parent.getFileName().toString().equals(TODO_DIR_NAME)
                                && grandParent.getFileName().toString().equals(UserContext.APPS_DIR_NAME);
                    } catch (Exception e) {
                        return false;
                    }
                }).collect(Collectors.toList());

        public Todo(UserContext ctx) {
            this.ctx = ctx;
        }

        public CompletableFuture<Pair<TodoBoard, Boolean>> getTodoBoard(String filename) {
            return getTodoBoard(this.ctx.username, filename);
        }

        @JsMethod
        public CompletableFuture<Pair<TodoBoard, Boolean>> getTodoBoard(String owner, String boardName) {
            Path path = Paths.get(owner, UserContext.APPS_DIR_NAME, TODO_DIR_NAME);
            return ctx.getByPath(path).thenCompose(fw -> {
                if (fw.isPresent()) {
                    FileWrapper todoDir = fw.get();
                    return todoDir.getChild(boardName + TODO_FILE_EXTENSION, ctx.crypto.hasher, ctx.network).thenCompose(todoFileOpt -> {
                        if (todoFileOpt.isEmpty()) {
                            return CompletableFuture.completedFuture(new Pair<>(TodoBoard.build(boardName, new ArrayList<>()), false));
                        }
                        FileWrapper todoFile = todoFileOpt.get();
                        FileProperties props = todoFile.getFileProperties();
                        int size = props.sizeLow();
                        return todoFile.getInputStream(ctx.network, ctx.crypto, x -> {
                        }).thenCompose(reader -> {
                            byte[] data = new byte[size];
                            return reader.readIntoArray(data, 0, data.length)
                                    .thenApply(x -> new Pair<>(TodoBoard.fromCbor(props.modified,
                                            CborObject.fromByteArray(data)), todoFile.isWritable()));
                        });
                    });
                } else {
                    return CompletableFuture.completedFuture(new Pair<>(TodoBoard.build(boardName, new ArrayList<>()), false));
                }
            });
        }

        private String extractTodoBoardName(String filename) {
            if (filename.indexOf('/') > -1) {
                filename = filename.substring(filename.lastIndexOf('/') + 1);
            }
            return filename.substring(0, filename.length() - TODO_FILE_EXTENSION.length());
        }

        @JsMethod
        public CompletableFuture<List<Pair<String, String>>> getTodoBoards() {
            Path path = Paths.get(ctx.username, UserContext.APPS_DIR_NAME, TODO_DIR_NAME);
            return ctx.getByPath(path).thenCompose(fw -> {
                if (fw.isPresent()) {
                    return CompletableFuture.completedFuture(fw.get());
                } else {
                    return ctx.getUserRoot()
                            .thenCompose(root -> root.getOrMkdirs(Paths.get(UserContext.APPS_DIR_NAME, TODO_DIR_NAME)
                                    , ctx.network, true, ctx.crypto))
                            .thenApply(dir -> dir);
                }
            }).thenCompose(dir -> dir.getChildren(ctx.crypto.hasher, ctx.network).thenApply(children ->
                    children.stream().filter(f -> f.getFileProperties().name.endsWith(TODO_FILE_EXTENSION))
                            .map(file -> new Pair<>(ctx.username, extractTodoBoardName(file.getFileProperties().name)))
                            .collect(Collectors.toList()))
            ).thenCompose(ourTodoBoards -> getSharedTodoBoards().thenApply(sharedTodoBoards ->
                    Stream.concat(ourTodoBoards.stream(), sharedTodoBoards.stream())
                            .sorted(todoListSorter)
                            .collect(Collectors.toList())
            ));
        }

        private CompletableFuture<List<Pair<String, String>>> getSharedTodoBoards() {
            Path path = Paths.get(ctx.username, UserContext.APPS_DIR_NAME, TODO_DIR_NAME);
            Path cacheFilePath = path.resolve(SHARED_WITH_US_CACHE_FILENAME);
            Path propsPath = path.resolve(PROPERTIES_FILENAME);

            return getSharedItems(ctx, cacheFilePath, propsPath, sharedItemsFilter).thenApply(sharedWithUs -> {
                if (sharedWithUs.isEmpty()) {
                    return Collections.emptyList();
                }
                return sharedWithUs.stream()
                        .map(item -> new Pair<>(item.sharer, extractTodoBoardName(item.path)))
                        .collect(Collectors.toList());
            });
        }

        @JsMethod
        public CompletableFuture<TodoBoard> updateTodoBoard(String owner, TodoBoard todoBoard) {
            Path path = Paths.get(owner, UserContext.APPS_DIR_NAME, TODO_DIR_NAME);
            String filename = todoBoard.getName() + TODO_FILE_EXTENSION;
            return ctx.getByPath(path).thenCompose(fw -> {
                if (fw.isPresent()) {
                    return CompletableFuture.completedFuture(fw.get());
                } else {
                    if (!owner.equals(ctx.username)) {
                        throw new IllegalStateException("Todo Board no longer available!");
                    }
                    return ctx.getUserRoot()
                            .thenCompose(root -> root.getOrMkdirs(Paths.get(UserContext.APPS_DIR_NAME, TODO_DIR_NAME)
                                    , ctx.network, true, ctx.crypto))
                            .thenApply(dir -> dir);
                }
            }).thenCompose(dir ->
                    dir.getUpdatedChild(filename, ctx.crypto.hasher, ctx.network).thenCompose(file -> {
                        byte[] bytes = todoBoard.serialize();
                        if (file.isPresent()) {
                            FileWrapper existingFile = file.get();
                            LocalDateTime modified = existingFile.getFileProperties().modified;
                            LocalDateTime current = todoBoard.getTimestamp();
                            if (current.equals(modified)) {
                                return file.get().overwriteFile(AsyncReader.build(bytes), bytes.length, ctx.network, ctx.crypto,
                                        x -> {
                                        })
                                        .thenApply(updatedFile -> {
                                            LocalDateTime newTimestamp = updatedFile.getFileProperties().modified;
                                            TodoBoard tb = TodoBoard.buildWithTimestamp(todoBoard.getName(),
                                                    todoBoard.getTodoLists(), newTimestamp);
                                            return tb;
                                        });
                            } else {
                                throw new IllegalStateException("Todo Board out-of-date! Please close and re-open");
                            }
                        } else {
                            return dir.uploadOrReplaceFile(todoBoard.getName() + TODO_FILE_EXTENSION, AsyncReader.build(bytes),
                                    bytes.length, ctx.network, ctx.crypto, x -> {
                                    }, ctx.crypto.random.randomBytes(32))
                                    .thenCompose(res -> res.getChild(filename, ctx.crypto.hasher, ctx.network))
                                    .thenApply(updatedFile -> TodoBoard.buildWithTimestamp(todoBoard.getName(),
                                            todoBoard.getTodoLists(), updatedFile.get().getFileProperties().modified));
                        }
                    }));
        }

        @JsMethod
        public CompletableFuture<Boolean> deleteTodoBoard(String owner, String filename) {
            Path path = Paths.get(owner, UserContext.APPS_DIR_NAME, TODO_DIR_NAME);
            return ctx.getByPath(path).thenCompose(dir -> {
                if (!dir.isPresent()) {
                    return CompletableFuture.completedFuture(false);
                }
                Path pathToFile = path.resolve(filename + TODO_FILE_EXTENSION);
                return dir.get().getChild(filename + TODO_FILE_EXTENSION, ctx.crypto.hasher, ctx.network)
                        .thenCompose(file -> file.get().remove(dir.get(), pathToFile, ctx).thenApply(fw -> true));
            });
        }
    }
}
