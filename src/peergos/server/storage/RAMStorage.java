package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class RAMStorage implements ContentAddressedStorage {
    private static final int CID_V1 = 1;

    private Map<Multihash, byte[]> storage = new EfficientHashMap<>();

    private final Set<Multihash> pinnedRoots = new HashSet<>();

    @Override
    public CompletableFuture<Multihash> id() {
        return CompletableFuture.completedFuture(new Multihash(Multihash.Type.sha2_256, new byte[32]));
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return CompletableFuture.completedFuture(new TransactionId(Long.toString(System.currentTimeMillis())));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signatures,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return put(writer, blocks, false);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid) {
        return put(writer, blocks, true);
    }

    private CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> blocks, boolean isRaw) {
        return CompletableFuture.completedFuture(blocks.stream()
                .map(b -> {
                    Cid cid = hashToCid(b, isRaw);
                    put(cid, b);
                    return cid;
                }).collect(Collectors.toList()));
    }

    private synchronized void put(Cid cid, byte[] data) {
        storage.put(cid, data);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash object) {
        return CompletableFuture.completedFuture(storage.containsKey(object) ?
                Optional.of(storage.get(object)) :
                Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        if (hash instanceof Cid && ((Cid) hash).codec == Cid.Codec.Raw)
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return CompletableFuture.completedFuture(getAndParseObject(hash));
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
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h) {
        return CompletableFuture.completedFuture(Arrays.asList(h));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h) {
        return CompletableFuture.completedFuture(Arrays.asList(h));
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return CompletableFuture.completedFuture(Arrays.asList(existing, updated));
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        if (root instanceof Cid && ((Cid) root).codec == Cid.Codec.Raw)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return get(root).thenApply(opt -> opt
                .map(cbor -> cbor.links())
                .orElse(Collections.emptyList())
        );
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

        for (Multihash ourKey : storage.keySet()) {
            if (! Arrays.equals(storage.get(ourKey), ((RAMStorage) o).storage.get(ourKey)))
                return false;
        }
        for (Multihash theirKey : ((RAMStorage) o).storage.keySet()) {
            if (! Arrays.equals(storage.get(theirKey), ((RAMStorage) o).storage.get(theirKey)))
                return false;
        }
        return pinnedRoots != null ? pinnedRoots.equals(that.pinnedRoots) : that.pinnedRoots == null;
    }

    @Override
    public int hashCode() {
        int result = storage != null ? storage.hashCode() : 0;
        result = 31 * result + (pinnedRoots != null ? pinnedRoots.hashCode() : 0);
        return result;
    }

    public int totalSize() {
        return storage.values().stream().mapToInt(a -> a.length).sum();
    }

    private static RAMStorage singleton = new RAMStorage();
    public static RAMStorage getSingleton() {
        return singleton;
    }
}
