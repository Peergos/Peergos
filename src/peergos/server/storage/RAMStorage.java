package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class RAMStorage implements DeletableContentAddressedStorage {
    private static final int CID_V1 = 1;

    private Map<Cid, byte[]> storage = new EfficientHashMap<>();
    private Map<TransactionId, List<Cid>> openTransactions = new ConcurrentHashMap<>();
    private final Set<Cid> pinnedRoots = new HashSet<>();
    private final Hasher hasher;

    public RAMStorage(Hasher hasher) {
        this.hasher = hasher;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return CompletableFuture.completedFuture(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]));
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
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        return getChampLookup(root, champKey, bat, hasher);
    }

    @Override
    public Stream<Cid> getAllBlockHashes() {
        return storage.keySet().stream();
    }

    @Override
    public void delete(Multihash hash) {
        storage.remove(hash);
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        return openTransactions.values()
                .stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return put(writer, blocks, false, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return put(writer, blocks, true, tid);
    }

    private CompletableFuture<List<Cid>> put(PublicKeyHash writer, List<byte[]> blocks, boolean isRaw, TransactionId tid) {
        return CompletableFuture.completedFuture(blocks.stream()
                .map(b -> {
                    Cid cid = hashToCid(b, isRaw);
                    put(cid, b);
                    openTransactions.get(tid).add(cid);
                    return cid;
                }).collect(Collectors.toList()));
    }

    private synchronized void put(Cid cid, byte[] data) {
        storage.put(cid, data);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid object, String auth) {
        return CompletableFuture.completedFuture(storage.containsKey(object) ?
                Optional.of(storage.get(object)) :
                Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        return getRaw(hash, bat, id().join(), hasher);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, String auth) {
        if (hash.codec == Cid.Codec.Raw)
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return CompletableFuture.completedFuture(getAndParseObject(hash));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        return get(hash, bat, id().join(), hasher);
    }

    private synchronized Optional<CborObject> getAndParseObject(Multihash hash) {
        if (! storage.containsKey(hash))
            return Optional.empty();
        return Optional.of(CborObject.fromByteArray(storage.get(hash)));
    }

    public synchronized void clear() {
        storage.clear();
    }

    public synchronized int size() {
        return storage.size();
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(Cid root, String auth) {
        if (root.codec == Cid.Codec.Raw)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return get(root, auth).thenApply(opt -> opt
                .map(cbor -> cbor.links().stream().map(c -> (Cid)c).collect(Collectors.toList()))
                .orElse(Collections.emptyList())
        );
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return storage.containsKey(hash);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        if (!storage.containsKey(block))
            return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.completedFuture(Optional.of(storage.get(block).length));
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
        return storage.values().stream().mapToInt(a -> a.length).sum();
    }
}
