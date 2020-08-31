package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class SocialFeed {
    private static final String FEED_FILE = "feed.cbor";
    private static final String FEED_INDEX = "feed-index.cbor";
    private static final String FEED_STATE = "feed-state.cbor";

    private FileWrapper dataDir, stateFile;
    private int lastSeenIndex, feedSizeRecords;
    private long feedSizeBytes;
    private Map<String, UserState> currentCapBytesProcessed;
    private final UserContext context;

    public SocialFeed(FileWrapper dataDir,
                      FileWrapper stateFile,
                      FeedState state,
                      UserContext context) {
        this.dataDir = dataDir;
        this.stateFile = stateFile;
        this.lastSeenIndex = state.lastSeenIndex;
        this.feedSizeRecords = state.feedSizeRecords;
        this.feedSizeBytes = state.feedSizeBytes;
        this.currentCapBytesProcessed = new HashMap<>(state.currentCapBytesProcessed);
        this.context = context;
    }

    @JsMethod
    public synchronized boolean hasUnseen() {
        return lastSeenIndex < feedSizeRecords;
    }

    @JsMethod
    public synchronized int getLastSeenIndex() {
        return lastSeenIndex;
    }

    public CompletableFuture<Boolean> setLastSeenIndex(int newLastSeenIndex) {
        this.lastSeenIndex = newLastSeenIndex;
        return commit();
    }

    /**
     *
     * @param index
     * @return the byte offset and corresponding index of a prior object boundary, which ideally should be in the same chunk
     */
    private CompletableFuture<Pair<Long, Integer>> getPriorByteOffset(int index) {
        return dataDir.getChild(FEED_INDEX, context.crypto.hasher, context.network)
                .thenCompose(fopt -> {
                    //TODO
//                    if (fopt.isEmpty())
//                        throw new IllegalStateException("Social feed state file not present!");
                    return Futures.of(new Pair<>(0L, 0));
                });
    }

    public CompletableFuture<List<SharedItem>> getShared(int from, int to, Crypto crypto, NetworkAccess network) {
        return getPriorByteOffset(from)
                .thenCompose(start -> dataDir.getChild(FEED_FILE, crypto.hasher, network)
                        .thenCompose(fopt -> fopt.get().getInputStream(network, crypto, x -> {})
                                .thenCompose(stream -> stream.seek(start.left)))
                        .thenCompose(stream -> {
                            List<SharedItem> res = new ArrayList<>();
                            return stream.parseLimitedStream(SharedItem::fromCbor, res::add,
                                    from - start.right, Math.min(feedSizeRecords, to) - from, feedSizeBytes)
                                    .thenApply(x -> res);
                        }));
    }

    private CompletableFuture<Boolean> commit() {
        byte[] raw = new FeedState(lastSeenIndex, feedSizeRecords, feedSizeBytes, currentCapBytesProcessed).serialize();
        return stateFile.overwriteFile(AsyncReader.build(raw), raw.length,
                context.network, context.crypto, x -> {})
                .thenApply(f -> {
                    this.stateFile = f;
                    return true;
                });
    }

    /** Incorporate any new shares from friends into the feed
     *
     * @return
     */
    @JsMethod
    public CompletableFuture<SocialFeed> update() {
        return context.getFollowingNodes()
                .thenCompose(friends -> Futures.reduceAll(friends, this, SocialFeed::updateFriend, (a, b) -> b))
                .thenCompose(x -> x.commit().thenApply(b -> x));
    }

    private synchronized CompletableFuture<SocialFeed> updateFriend(FriendSourcedTrieNode friend) {
        return friend.ensureUptodate(context.crypto, context.network)
                .thenCompose(diff -> {
                    if (diff.isEmpty())
                        return Futures.of(this);
                    UserState current = currentCapBytesProcessed.getOrDefault(friend.ownerName, UserState.empty());
                    if (diff.priorBytes() == current.totalBytes()) {
                        // We have everything we need in the diff
                        return addToFriend(friend.ownerName, current, diff.newCaps.readCaps.getRetrievedCapabilities(),
                                diff.updatedReadBytes(),
                                diff.newCaps.writeCaps.getRetrievedCapabilities(),
                                diff.updatedWriteBytes());
                    }
                    // There must have been concurrent processing of new caps, e.g. by browsing to that user's files
                    // We need to load the whole lot to catch what we missed
                    return friend.loadCachedCaps(context.network, context.crypto)
                            .thenCompose(all -> {
                                List<CapabilityWithPath> readCapsToAdd = Stream.of(all.readCaps, diff.newCaps.readCaps)
                                        .flatMap(c -> c.getRetrievedCapabilities().stream())
                                        .skip(current.readCaps)
                                        .collect(Collectors.toList());
                                List<CapabilityWithPath> writeCapsToAdd = Stream.of(all.writeCaps, diff.newCaps.writeCaps)
                                        .flatMap(c -> c.getRetrievedCapabilities().stream())
                                        .skip(current.writeCaps)
                                        .collect(Collectors.toList());
                                return addToFriend(friend.ownerName, current, readCapsToAdd, all.readCaps.getBytesRead(),
                                        writeCapsToAdd, all.writeCaps.getBytesRead());
                            });
                });
    }

    private static String extractOwner(String path) {
        int start = path.startsWith("/") ? 1 : 0;
        int end = path.indexOf("/", start + 1);
        return path.substring(start, end);
    }

    private synchronized CompletableFuture<SocialFeed> addToFriend(String friendName,
                                                                   UserState current,
                                                                   List<CapabilityWithPath> readCapsToAdd,
                                                                   long updatedReadCapBytesTotal,
                                                                   List<CapabilityWithPath> writeCapsToAdd,
                                                                   long updateWriteCapBytesTotal) {
        UserState updated = new UserState(
                current.readCaps + readCapsToAdd.size(),
                current.writeCaps + writeCapsToAdd.size(),
                updatedReadCapBytesTotal,
                updateWriteCapBytesTotal);
        currentCapBytesProcessed.put(friendName, updated);
        feedSizeRecords += readCapsToAdd.size() + writeCapsToAdd.size();
        List<SharedItem> forFeed = Stream.of(readCapsToAdd, writeCapsToAdd)
                .flatMap(List::stream)
                .map(c -> new SharedItem(c.cap, extractOwner(c.path), friendName, c.path))
                .collect(Collectors.toList());
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        for (SharedItem item : forFeed) {
            try {
                bout.write(item.serialize());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        byte[] data = bout.toByteArray();
        feedSizeBytes += data.length;
        return dataDir.appendToChild(FEED_FILE, data, false, context.network, context.crypto, x -> {})
                .thenCompose(dir -> {
                    this.dataDir = dir;
                    return commit();
                })
                .thenApply(x -> this);
    }

    public static CompletableFuture<SocialFeed> load(FileWrapper dataDir, UserContext context) {
        return dataDir.getChild(FEED_STATE, context.crypto.hasher, context.network)
                .thenCompose(fopt -> {
                    if (fopt.isEmpty())
                        throw new IllegalStateException("Social feed state file not present!");
                    return Serialize.readFully(fopt.get(), context.crypto, context.network)
                            .thenApply(arr -> FeedState.fromCbor(CborObject.fromByteArray(arr)))
                            .thenApply(s -> new SocialFeed(dataDir, fopt.get(), s, context));
                });
    }

    public static CompletableFuture<SocialFeed> create(UserContext c) {
        return c.getUserRoot()
                .thenCompose(home -> home.mkdir(UserContext.FEED_DIR_NAME, c.network, true, c.crypto))
                .thenCompose(newHome -> {
                    FeedState empty = new FeedState(0, 0, 0L, Collections.emptyMap());
                    byte[] rawEmpty = empty.serialize();
                    return newHome.getChild(UserContext.FEED_DIR_NAME, c.crypto.hasher, c.network)
                            .thenApply(Optional::get)
                            .thenCompose(feedDir ->
                                    feedDir.uploadAndReturnFile(FEED_STATE, AsyncReader.build(rawEmpty), rawEmpty.length,
                                            false, c.network, c.crypto)
                                            .thenApply(stateFile -> new SocialFeed(feedDir, stateFile, empty, c))
                                            .thenCompose(SocialFeed::update));
                });
    }

    private static class UserState implements Cborable {
        public final int readCaps, writeCaps;
        public final long readCapBytes, writeCapBytes;

        public UserState(int readCaps, int writeCaps, long readCapBytes, long writeCapBytes) {
            this.readCaps = readCaps;
            this.writeCaps = writeCaps;
            this.readCapBytes = readCapBytes;
            this.writeCapBytes = writeCapBytes;
        }

        public long totalBytes() {
            return readCapBytes + writeCapBytes;
        }

        public static UserState empty() {
            return new UserState(0, 0, 0L, 0L);
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("rc", new CborObject.CborLong(readCaps));
            state.put("wc", new CborObject.CborLong(writeCaps));
            state.put("rb", new CborObject.CborLong(readCapBytes));
            state.put("wb", new CborObject.CborLong(writeCapBytes));
            return CborObject.CborMap.build(state);
        }

        public static UserState fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for UserState! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;

            int readCaps = (int) m.getLong("rc");
            int writeCaps = (int) m.getLong("wc");
            long readCapBytes = m.getLong("rb");
            long writeCapBytes = m.getLong("wb");
            return new UserState(readCaps, writeCaps, readCapBytes, writeCapBytes);
        }
    }

    private static class FeedState implements Cborable {
        public final int lastSeenIndex, feedSizeRecords;
        public final long feedSizeBytes;
        public final Map<String, UserState> currentCapBytesProcessed;

        public FeedState(int lastSeenIndex, int feedSizeRecords, long feedSizeBytes, Map<String, UserState> currentCapBytesProcessed) {
            this.lastSeenIndex = lastSeenIndex;
            this.feedSizeRecords = feedSizeRecords;
            this.feedSizeBytes = feedSizeBytes;
            this.currentCapBytesProcessed = currentCapBytesProcessed;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("s", new CborObject.CborLong(lastSeenIndex));
            state.put("r", new CborObject.CborLong(feedSizeRecords));
            state.put("b", new CborObject.CborLong(feedSizeBytes));
            SortedMap<String, Cborable> processed = new TreeMap<>();
            for (Map.Entry<String, UserState> e : currentCapBytesProcessed.entrySet()) {
                processed.put(e.getKey(), e.getValue().toCbor());
            }
            state.put("p", CborObject.CborMap.build(processed));
            return CborObject.CborMap.build(state);
        }

        public static FeedState fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for FeedState! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;

            int lastSeenIndex = (int) m.getLong("s");
            int feedSizeRecords = (int) m.getLong("r");
            long feedSizeBytes = m.getLong("b");
            Map<String, UserState> processedBytes = ((CborObject.CborMap)m.get("p"))
                    .getMap(c -> ((CborObject.CborString) c).value, UserState::fromCbor);
            return new FeedState(lastSeenIndex, feedSizeRecords, feedSizeBytes, processedBytes);
        }
    }
}
