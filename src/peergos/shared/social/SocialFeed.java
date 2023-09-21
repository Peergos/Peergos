package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.display.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
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
    private final NetworkAccess network;
    private final Crypto crypto;

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
        this.network = context.network;
        this.crypto = context.crypto;
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
        String postFilename = UUID.randomUUID().toString() + ".cbor";
        Path dir = getDirFromHome(post);
        byte[] raw = post.serialize();
        AsyncReader reader = AsyncReader.build(raw);
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(dir, network, true, context.mirrorBatId(), crypto))
                .thenCompose(postDir -> postDir.uploadAndReturnFile(postFilename, reader, raw.length, false,
                        postDir.mirrorBatId(), network, crypto)
                        .thenApply(f -> new Pair<>(PathUtil.get(post.author).resolve(dir).resolve(postFilename), f)))
                .thenCompose(p -> addToFeed(p.right.version, Arrays.asList(new SharedItem(p.right.readOnlyPointer(),
                        context.username, context.username, p.left.toString())))
                        .thenApply(f -> p));
    }

    @JsMethod
    public CompletableFuture<Pair<Path, FileWrapper>> updatePost(String uuid, SocialPost post) {
        if (! post.author.equals(context.username))
            throw new IllegalStateException("You can only post as yourself!");
        Path dir = getDirFromHome(post.previousVersions.get(0));
        byte[] raw = post.serialize();
        String completePath = context.username + "/" + dir.resolve(uuid).toString();
        return context.getByPath(completePath).thenCompose(fopt ->
            fopt.get().overwriteFile(AsyncReader.build(raw), raw.length, network, crypto, x -> {})
                    .thenApply(f -> new Pair<>(PathUtil.get(post.author).resolve(dir).resolve(uuid), f))
        );
    }

    @JsMethod
    public CompletableFuture<Pair<String, FileRef>> uploadMediaForPost(AsyncReader media,
                                                                       int length,
                                                                       LocalDateTime postTime,
                                                                       ProgressConsumer<Long> monitor) {
        String uuid = UUID.randomUUID().toString();
        return getOrMkdirToStoreMedia("media", postTime)
                .thenCompose(p -> p.right.uploadAndReturnFile(uuid, media, length, false, monitor,
                        p.right.mirrorBatId(), network, crypto)
                        .thenCompose(f ->  media.reset().thenCompose(r -> crypto.hasher.hashFromStream(r, length))
                                .thenApply(hash -> new Pair<>(f.getFileProperties().getType(),
                                        new FileRef(p.left.resolve(uuid).toString(), f.readOnlyPointer(), hash)))));
    }

    private CompletableFuture<Pair<Path, FileWrapper>> getOrMkdirToStoreMedia(String mediaType, LocalDateTime postTime) {
        Path dirFromHome = PathUtil.get(UserContext.POSTS_DIR_NAME,
                Integer.toString(postTime.getYear()),
                mediaType);
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(dirFromHome, network, true, context.mirrorBatId(), crypto)
                .thenApply(dir -> new Pair<>(PathUtil.get("/" + context.username).resolve(dirFromHome), dir)));
    }

    public static Path getDirFromHome(SocialPost post) {
        return PathUtil.get(UserContext.POSTS_DIR_NAME,
                Integer.toString(post.postTime.getYear()),
                Integer.toString(post.postTime.getMonthValue()));
    }

    @JsMethod
    public synchronized boolean hasUnseen() {
        return lastSeenIndex < feedSizeRecords;
    }

    @JsMethod
    public synchronized int getFeedSize() {
        return feedSizeRecords;
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
        return dataDir.getChild(FEED_INDEX, crypto.hasher, network)
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

    @JsMethod
    public CompletableFuture<List<Pair<SharedItem, FileWrapper>>> getSharedFiles(int from, int to) {
        return getShared(from, to, crypto, network)
                .thenCompose(context::getFiles);
    }

    private CompletableFuture<List<Pair<SharedItem, FileWrapper>>> mergeCommentReferences(List<Pair<SharedItem, FileWrapper>> items) {
        List<Pair<SharedItem, FileWrapper>> posts = items.stream()
                .filter(p -> p.right.getFileProperties().isSocialPost())
                .collect(Collectors.toList());

        return Futures.combineAllInOrder(posts.stream()
                .map(p -> Serialize.parse(p.right, SocialPost::fromCbor, network, crypto)
                        .thenApply(sp -> new Triple<>(p.left, p.right, sp)))
                .collect(Collectors.toList()))
                .thenCompose(retrieved -> {
                    Map<String, List<Triple<SharedItem, FileWrapper, SocialPost>>> commentsOnOurs = retrieved.stream()
                            .filter(t -> t.right.parent.map(p -> p.path.startsWith("/" + context.username)).orElse(false))
                            .collect(Collectors.groupingBy(t -> t.right.parent.get().path));
                    return Futures.combineAllInOrder(commentsOnOurs.entrySet().stream()
                    .map(e -> mergeCommentsIntoParent(e.getKey(), e.getValue()))
                    .collect(Collectors.toList()));
                }).thenApply(x -> items);
    }

    private CompletableFuture<Boolean> mergeCommentsIntoParent(String parentPath,
                                                               List<Triple<SharedItem, FileWrapper, SocialPost>> comments) {
        return Futures.combineAllInOrder(comments.stream().map(t -> t.middle.getInputStream(network, crypto, x -> {})
                .thenCompose(reader -> crypto.hasher.hashFromStream(reader, t.middle.getSize())
                        .thenApply(h -> new FileRef(t.left.path, t.left.cap, h))))
                .collect(Collectors.toList())).thenCompose(refs ->
                context.getByPath(parentPath).thenCompose(fopt -> {
                    if (fopt.isEmpty())
                        return Futures.of(false);
                    if (! fopt.get().getFileProperties().isSocialPost())
                        return Futures.of(true);
                    return Serialize.parse(fopt.get(), SocialPost::fromCbor, network, crypto)
                            .thenCompose(parent -> {
                                SocialPost withComments = parent.addComments(refs);
                                byte[] raw = withComments.serialize();
                                return fopt.get().overwriteFile(AsyncReader.build(raw), raw.length, network, crypto, x -> {})
                                        .thenApply(x -> {
                                            dataDir = dataDir.withVersion(x.version);
                                            return true;
                                        });
                            });
                }));
    }

    private synchronized CompletableFuture<Boolean> commit() {
        byte[] raw = new FeedState(lastSeenIndex, feedSizeRecords, feedSizeBytes, currentCapBytesProcessed).serialize();
        return stateFile.overwriteFile(AsyncReader.build(raw), raw.length,
                network, crypto, x -> {})
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
        PublicKeyHash owner = context.signer.publicKeyHash;
        return network.synchronizer.applyComplexComputation(owner, dataDir.signingPair(),
                (s, c) -> {
                    return context.getFollowingNodes()
                            .thenCompose(friends -> {
                                List<CompletableFuture<Optional<Pair<FriendSourcedTrieNode,Snapshot>>>> pointers = friends.stream()
                                        .map(f -> f.getLatestVersion(network).thenApply(v -> Optional.of(new Pair<>(f, v))).exceptionally(t -> Optional.empty()))
                                        .collect(Collectors.toList());
                                return Futures.combineAllInOrder(pointers)
                                        .thenApply(pairs -> pairs.stream()
                                                .flatMap(Optional::stream)
                                                .map(p -> new Pair<>(Stream.of(p.left), p.right))
                                                .reduce((a, b) -> new Pair<>(Stream.concat(a.left, b.left), a.right.merge(b.right))))
                                        .thenApply(combined ->  new Pair<>(
                                                combined.map(p ->  p.left.collect(Collectors.toSet())).orElse(Collections.emptySet()),
                                                combined.map(p ->  p.right).map(s::mergeAndOverwriteWith).orElse(s)));
                            })
                            .thenCompose(fv -> Futures.reduceAll(fv.left.stream(), new Pair<>(fv.right, Stream.<Update>empty()),
                                    (p, friend) -> getFriendUpdate(friend, p.left, c, network)
                                            .thenApply(res -> new Pair<>(res.left, Stream.concat(p.right, res.right.stream()))),
                                    (a, b) -> b));
                }
        ).thenCompose(updates -> mergeUpdates(updates.left, updates.right.collect(Collectors.toList())));
    }

    private static class Update extends Triple<String, ProcessedCaps, CapsDiff> {
        public Update(String left, ProcessedCaps middle, CapsDiff right) {
            super(left, middle, right);
        }
    }

    private CompletableFuture<Pair<Snapshot, Optional<Update>>> getFriendUpdate(FriendSourcedTrieNode friend,
                                                                                Snapshot s,
                                                                                Committer c,
                                                                                NetworkAccess network) {
        ProcessedCaps current = currentCapBytesProcessed.getOrDefault(friend.ownerName, ProcessedCaps.empty());
        return friend.updateIncludingGroups(s, c, network)
                .thenCompose(p -> friend.getCaps(current, s,network)
                        .thenApply(diff -> {
                            if (diff.isEmpty())
                                return new Pair<>(p.left, Optional.<Update>empty());
                            return new Pair<>(p.left, Optional.of(new Update(friend.ownerName, current, diff)));
                        }));
    }

    private synchronized CompletableFuture<SocialFeed> mergeUpdates(Snapshot v, Collection<Triple<String, ProcessedCaps, CapsDiff>> updates) {
        List<SharedItem> forFeed = new ArrayList<>();
        for (Triple<String, ProcessedCaps, CapsDiff> update : updates) {
            ProcessedCaps updated = update.middle.add(update.right);
            currentCapBytesProcessed.put(update.left, updated);
            List<CapabilityWithPath> newCaps = update.right.getNewCaps();
            newCaps.stream()
                    .map(c -> new SharedItem(c.cap, extractOwner(c.path), update.left, c.path))
                    .forEach(forFeed::add);
        }
        return addToFeed(v, forFeed);
    }

    private CompletableFuture<List<Pair<SharedItem, FileWrapper>>> mergeInComments(List<SharedItem> shared) {
        return context.getFiles(shared)
                .thenCompose(this::mergeCommentReferences);
    }

    private static String extractOwner(String path) {
        return PathUtil.get(path).getName(0).toString();
    }

    private synchronized CompletableFuture<SocialFeed> addToFriend(Snapshot v,
                                                                   String friendName,
                                                                   ProcessedCaps current,
                                                                   CapsDiff diff) {
        ProcessedCaps updated = current.add(diff);
        currentCapBytesProcessed.put(friendName, updated);
        List<CapabilityWithPath> newCaps = diff.getNewCaps();
        List<SharedItem> forFeed = newCaps.stream()
                .map(c -> new SharedItem(c.cap, extractOwner(c.path), friendName, c.path))
                .collect(Collectors.toList());
        return addToFeed(v, forFeed);
    }

    private synchronized CompletableFuture<SocialFeed> addToFeed(Snapshot v, List<SharedItem> newItems) {
        if (newItems.isEmpty())
            return setVersion(v).thenApply(x -> this);
        return mergeInComments(newItems).thenCompose(b -> {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            for (SharedItem item : newItems) {
                try {
                    bout.write(item.serialize());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            byte[] data = bout.toByteArray();
            return Futures.asyncExceptionally(() -> appendToFeedAndCommitState(data, newItems.size()),
                    t -> ensureFeedUptodate().thenCompose(x -> appendToFeedAndCommitState(data, newItems.size())))
                    .thenApply(x -> this);
        });
    }

    private synchronized CompletableFuture<Snapshot> appendToFeedAndCommitState(byte[] data, int records) {
        PublicKeyHash owner = context.signer.publicKeyHash;
        // use a buffered network to make this atomic across multiple files
        return network.synchronizer.applyComplexUpdate(dataDir.owner(), dataDir.signingPair(),
                (s, c) -> {
                    return dataDir.getUpdated(s, network).thenCompose(updated ->
                            updated.getChild(FEED_FILE, crypto.hasher, network).thenCompose(feedOpt -> {
                                if (feedOpt.isEmpty())
                                    return updated.uploadFileSection(updated.version, c, FEED_FILE, AsyncReader.build(data),
                                            false, 0, data.length, Optional.empty(), false, false,
                                            false, network, crypto, x -> {},
                                            crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH),
                                            Optional.empty(),  Optional.of(Bat.random(crypto.random)), updated.mirrorBatId());
                                if (feedOpt.get().getSize() != feedSizeBytes)
                                    throw new IllegalStateException("Feed size incorrect!");
                                return feedOpt.get().append(data, network, crypto, c, x -> {});
                            })).thenCompose(s2 -> {
                        feedSizeRecords += records;
                        feedSizeBytes += data.length;
                        byte[] raw = new FeedState(lastSeenIndex, feedSizeRecords, feedSizeBytes, currentCapBytesProcessed).serialize();
                        return stateFile.overwriteFile(AsyncReader.build(raw), raw.length, network, crypto, x -> {}, s2, c);
                    });
                }).thenCompose(this::setVersion);
    }

    private CompletableFuture<Snapshot> setVersion(Snapshot s) {
        return dataDir.getUpdated(s, network).thenApply(u -> {
            this.dataDir = u;
            return true;
        }).thenCompose(x -> this.stateFile.getUpdated(s, network).thenApply(us -> {
            this.stateFile = us;
            return s;
        }));
    }

    private CompletableFuture<Boolean> ensureFeedUptodate() {
        return getUpdatedState(dataDir).thenApply(p -> {
            this.dataDir = p.left;
            this.feedSizeBytes = p.right.feedSizeBytes;
            this.feedSizeRecords = p.right.feedSizeRecords;
            this.lastSeenIndex = p.right.lastSeenIndex;
            return true;
        });
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

    private CompletableFuture<Pair<FileWrapper, FeedState>> getUpdatedState(FileWrapper dataDir) {
        return dataDir.getUpdated(network)
                .thenCompose(updatedDataDir -> updatedDataDir.getChild(FEED_STATE, context.crypto.hasher, context.network)
                .thenCompose(fopt -> {
                    if (fopt.isEmpty())
                        throw new IllegalStateException("Social feed state file not present!");
                    return Serialize.readFully(fopt.get(), context.crypto, context.network)
                            .thenApply(arr -> new Pair<>(updatedDataDir, FeedState.fromCbor(CborObject.fromByteArray(arr))));
                }));
    }

    public static CompletableFuture<SocialFeed> create(UserContext c) {
        return c.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(PathUtil.get(UserContext.FEED_DIR_NAME), c.network, true, c.mirrorBatId(), c.crypto))
                .thenCompose(feedDir -> {
                    FeedState empty = new FeedState(0, 0, 0L, Collections.emptyMap());
                    byte[] rawEmpty = empty.serialize();
                    return feedDir.uploadAndReturnFile(FEED_STATE, AsyncReader.build(rawEmpty), rawEmpty.length,
                            false, feedDir.mirrorBatId(), c.network, c.crypto)
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
