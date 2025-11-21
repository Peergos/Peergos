package peergos.server.storage;

import peergos.server.storage.auth.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class RAMStorage implements DeletableContentAddressedStorage {
    private static final int CID_V1 = 1;

    private Map<PublicKeyHash, Map<Cid, byte[]>> storage = new EfficientHashMap<>();
    private Map<TransactionId, List<Cid>> openTransactions = new ConcurrentHashMap<>();
    private final Set<Cid> pinnedRoots = new HashSet<>();
    private final Hasher hasher;

    public RAMStorage(Hasher hasher) {
        this.hasher = hasher;
    }

    @Override
    public void setPki(CoreNode pki) {}

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return CompletableFuture.completedFuture(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]));
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return CompletableFuture.completedFuture(List.of(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32])));
    }

    @Override
    public CompletableFuture<String> linkHost(PublicKeyHash owner) {
        return Futures.of("localhost:8000");
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        TransactionId tid = new TransactionId(Long.toString(System.currentTimeMillis()));
        openTransactions.put(tid, new ArrayList<>());
        return CompletableFuture.completedFuture(tid);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        openTransactions.remove(tid);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
        return getChampLookup(owner, root, caps, committedRoot, hasher);
    }

    @Override
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(boolean useBlockstore) {
        return storage.keySet().stream().map(c -> new Pair<>(PublicKeyHash.NULL, c));
    }


    @Override
    public void getAllBlockHashVersions(Consumer<List<BlockVersion>> res) {
        res.accept(getAllBlockHashes(false)
                .map(p -> new BlockVersion(p.right, null, true))
                .collect(Collectors.toList()));
    }

    @Override
    public void delete(Cid hash) {
        storage.remove(hash);
    }

    @Override
    public List<Cid> getOpenTransactionBlocks() {
        return openTransactions.values()
                .stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    @Override
    public void clearOldTransactions(long cutoffMillis) {

    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return put(owner, blocks, false, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return put(owner, blocks, true, tid);
    }

    private CompletableFuture<List<Cid>> put(PublicKeyHash owner, List<byte[]> blocks, boolean isRaw, TransactionId tid) {
        return CompletableFuture.completedFuture(blocks.stream()
                .map(b -> {
                    Cid cid = hashToCid(b, isRaw);
                    put(owner, cid, b);
                    openTransactions.get(tid).add(cid);
                    return cid;
                }).collect(Collectors.toList()));
    }

    private synchronized void put(PublicKeyHash owner, Cid cid, byte[] data) {
        Map<Cid, byte[]> userStorage = forUser(owner);
        userStorage.put(cid, data);
    }

    private Map<Cid, byte[]> forUser(PublicKeyHash owner) {
        Map<Cid, byte[]> res = storage.get(owner);
        if (res != null)
            return res;
        EfficientHashMap<Cid, byte[]> val = new EfficientHashMap<>();
        storage.put(owner, val);
        return val;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      Optional<BatWithId> bat,
                                                      Cid ourId,
                                                      Hasher h,
                                                      boolean doAuth,
                                                      boolean persistBlock) {
        Map<Cid, byte[]> userStorage = forUser(owner);
        return CompletableFuture.completedFuture(userStorage.containsKey(hash) ?
                Optional.of(userStorage.get(hash)) :
                Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid object, String auth, boolean persistBlock) {
        Map<Cid, byte[]> userStorage = forUser(owner);
        return CompletableFuture.completedFuture(userStorage.containsKey(object) ?
                Optional.of(userStorage.get(object)) :
                Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return getRaw(Collections.emptyList(), owner, hash, bat, id().join(), hasher, false);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        if (hash.codec == Cid.Codec.Raw)
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return CompletableFuture.completedFuture(getAndParseObject(owner, hash));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return get(Collections.emptyList(), owner, hash, bat, id().join(), hasher, false);
    }

    private synchronized Optional<CborObject> getAndParseObject(PublicKeyHash owner, Multihash hash) {
        Map<Cid, byte[]> userStorage = forUser(owner);
        if (! userStorage.containsKey(hash))
            return Optional.empty();
        return Optional.of(CborObject.fromByteArray(userStorage.get(hash)));
    }

    public synchronized void clear() {
        storage.clear();
    }

    public synchronized int size() {
        return storage.values().stream().mapToInt(Map::size).sum();
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
        return getRaw(Arrays.asList(id().join()), owner, block, Optional.empty(), id().join(), hasher, true)
                .thenApply(rawOpt -> BlockMetadataStore.extractMetadata(block, rawOpt.get()));
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid root, List<Multihash> peerids) {
        if (root.codec == Cid.Codec.Raw)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return get(peerids, owner, root, "", false).thenApply(opt -> opt
                .map(cbor -> cbor.links().stream().map(c -> (Cid)c).collect(Collectors.toList()))
                .orElse(Collections.emptyList())
        );
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return storage.containsKey(hash);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash block) {
        Map<Cid, byte[]> userStorage = forUser(owner);
        if (!userStorage.containsKey(block))
            return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.completedFuture(Optional.of(userStorage.get(block).length));
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
        throw new IllegalStateException("Shouldn't get here.");
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
        throw new IllegalStateException("Shouldn't get here.");
    }

    public static Cid hashToCid(byte[] input, boolean isRaw) {
        byte[] hash = hash(input);
        return new Cid(CID_V1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
    }

    public static byte[] hash(byte[] input)
    {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("couldn't find hash algorithm");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RAMStorage that = (RAMStorage) o;
        return Objects.equals(storage, that.storage) &&
                Objects.equals(openTransactions, that.openTransactions) &&
                Objects.equals(pinnedRoots, that.pinnedRoots) &&
                Objects.equals(hasher, that.hasher);
    }

    @Override
    public int hashCode() {
        return Objects.hash(storage, openTransactions, pinnedRoots, hasher);
    }

    public int totalSize() {
        return storage.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToInt(a -> a.length).sum();
    }

    @Override
    public Optional<BlockCache> getBlockCache() {
        return Optional.empty();
    }
}
