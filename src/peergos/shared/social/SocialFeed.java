package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** This social feed stores a list of caps shared with you.
 *
 *  Data is stored in /username/.feed/
 *                                    feed-state.cbor    - Your serialized FeedState
 *                                    feed-index.cbor    - An lookup from index in feed to byte offset in feed.cbor
 *                                    feed.cbor          - An append only list of serialized SharedItems
 *
 *  The FeedState stores how many bytes of the incoming cap file has been processed from each friend
 */
public class SocialFeed {
    private static final String FEED_FILE = "feed.cbor";
    private static final String FEED_INDEX = "feed-index.cbor";
    private static final String FEED_STATE = "feed-state.cbor";

    private FileWrapper dataDir, stateFile;
    private int lastSeenIndex, feedSizeRecords;
    private long feedSizeBytes;
    private Map<String, ProcessedCaps> currentCapBytesProcessed;
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

    /** Create a new post file under /username/.posts/$year/$month/#uuid
     *
     * @param post
     * @return
     */
    @JsMethod
    public CompletableFuture<Pair<Path, FileWrapper>> createNewPost(SocialPost post) {
        if (! post.author.equals(context.username))
            throw new IllegalStateException("You can only post as yourself!");
        String uuid = UUID.randomUUID().toString();
        Path dir = getDirFromHome(post);
        byte[] raw = post.serialize();
        AsyncReader reader = AsyncReader.build(raw);
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(dir, context.network, true, context.crypto))
                .thenCompose(postDir -> postDir.uploadAndReturnFile(uuid, reader, raw.length, false,
                        context.network, context.crypto)
                        .thenApply(f -> new Pair<>(Paths.get(post.author).resolve(dir).resolve(uuid), f)));
    }

    public static Path getDirFromHome(SocialPost post) {
        return Paths.get(UserContext.POSTS_DIR_NAME,
                Integer.toString(post.postTime.getYear()),
                Integer.toString(post.postTime.getMonthValue()));
    }

    @JsMethod
    public synchronized boolean hasUnseen() {
        return lastSeenIndex < feedSizeRecords;
    }

    @JsMethod
    public synchronized int getLastSeenIndex() {
        return lastSeenIndex;
    }

    @JsMethod
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

    @JsMethod
    public CompletableFuture<List<SharedItem>> getShared(int from, int to, Crypto crypto, NetworkAccess network) {
        return getPriorByteOffset(from)
                .thenCompose(start -> dataDir.getChild(FEED_FILE, crypto.hasher, network)
                        .thenCompose(fopt -> fopt.map(f -> f.getInputStream(network, crypto, x -> {})
                                .thenCompose(stream -> stream.seek(start.left))
                                .thenCompose(stream -> {
                                    List<SharedItem> res = new ArrayList<>();
                                    return stream.parseLimitedStream(SharedItem::fromCbor, res::add,
                                            from - start.right, Math.min(feedSizeRecords, to) - from, feedSizeBytes)
                                            .thenApply(x -> res);
                                })).orElse(Futures.of(Collections.emptyList()))));
    }

    private synchronized CompletableFuture<Boolean> commit() {
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
    public synchronized CompletableFuture<SocialFeed> update() {
        return context.getFollowingNodes()
                .thenCompose(friends -> Futures.reduceAll(friends, this,
                        (s, f) -> s.updateFriend(f, context.network), (a, b) -> b))
                .thenCompose(x -> x.commit().thenApply(b -> x));
    }

    private synchronized CompletableFuture<SocialFeed> updateFriend(FriendSourcedTrieNode friend, NetworkAccess network) {
        ProcessedCaps current = currentCapBytesProcessed.getOrDefault(friend.ownerName, ProcessedCaps.empty());
        return friend.getCaps(current, network)
                .thenCompose(diff -> {
                    if (diff.isEmpty())
                        return Futures.of(this);

                    return addToFriend(friend.ownerName, current, diff);
                });
    }

    private static String extractOwner(String path) {
        int start = path.startsWith("/") ? 1 : 0;
        int end = path.indexOf("/", start + 1);
        return path.substring(start, end);
    }

    private synchronized CompletableFuture<SocialFeed> addToFriend(String friendName,
                                                                   ProcessedCaps current,
                                                                   CapsDiff diff) {
        ProcessedCaps updated = current.add(diff);
        currentCapBytesProcessed.put(friendName, updated);
        List<CapabilityWithPath> newCaps = diff.getNewCaps();
        feedSizeRecords += newCaps.size();
        List<SharedItem> forFeed = newCaps.stream()
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
                .thenCompose(home -> home.getOrMkdirs(Paths.get(UserContext.FEED_DIR_NAME), c.network, true, c.crypto))
                .thenCompose(feedDir -> {
                    FeedState empty = new FeedState(0, 0, 0L, Collections.emptyMap());
                    byte[] rawEmpty = empty.serialize();
                    return feedDir.uploadAndReturnFile(FEED_STATE, AsyncReader.build(rawEmpty), rawEmpty.length,
                            false, c.network, c.crypto)
                            .thenApply(stateFile -> new SocialFeed(feedDir, stateFile, empty, c))
                            .thenCompose(SocialFeed::update);
                });
    }

    private static class FeedState implements Cborable {
        public final int lastSeenIndex, feedSizeRecords;
        public final long feedSizeBytes;
        public final Map<String, ProcessedCaps> currentCapBytesProcessed;

        public FeedState(int lastSeenIndex, int feedSizeRecords, long feedSizeBytes, Map<String, ProcessedCaps> currentCapBytesProcessed) {
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
            for (Map.Entry<String, ProcessedCaps> e : currentCapBytesProcessed.entrySet()) {
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
            Map<String, ProcessedCaps> processedBytes = ((CborObject.CborMap)m.get("p"))
                    .toMap(c -> ((CborObject.CborString) c).value, ProcessedCaps::fromCbor);
            return new FeedState(lastSeenIndex, feedSizeRecords, feedSizeBytes, processedBytes);
        }
    }
}
