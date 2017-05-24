package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.ContentAddressedStorage;

import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class RAMStorage implements ContentAddressedStorage {
    private static final int CID_V1 = 1;

    private Map<Multihash, byte[]> storage = new HashMap<>();

    private final Set<Multihash> pinnedRoots = new HashSet<>();

    @Override
    public CompletableFuture<List<Multihash>> put(PublicSigningKey writer, List<byte[]> blocks) {
        return CompletableFuture.completedFuture(blocks.stream()
                .map(b -> {
                    Cid cid = hashToCid(b);
                    put(cid, b);
                    return cid;
                }).collect(Collectors.toList()));
    }

    private synchronized void put(Cid cid, byte[] data) {
        storage.put(cid, data);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicSigningKey writer, List<byte[]> blocks) {
        return put(writer, blocks);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash object) {
        return CompletableFuture.completedFuture(Optional.of(storage.getOrDefault(object, new byte[0])));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash object) {
        return CompletableFuture.completedFuture(Optional.of(getAndParseObject(object)));
    }

    private synchronized CborObject getAndParseObject(Multihash hash) {
        if (!storage.containsKey(hash))
            throw new IllegalStateException("Hash not present! "+ hash);
        return CborObject.fromByteArray(storage.get(hash));
    }

    public synchronized void clear() {
        storage.clear();
    }

    public synchronized int size() {
        return storage.size();
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(Multihash h) {
        return CompletableFuture.completedFuture(Arrays.asList(h));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h) {
        return CompletableFuture.completedFuture(Arrays.asList(h));
    }

    @Override
    public CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated) {
        return CompletableFuture.completedFuture(Arrays.asList(new MultiAddress("/ipfs/"+existing), new MultiAddress("/ipfs/"+updated)));
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
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

    public static Cid hashToCid(byte[] input) {
        byte[] hash = hash(input);
        Multihash multihash = new Multihash(Multihash.Type.sha2_256, hash);
        return new Cid(CID_V1, Cid.Codec.DagCbor, multihash);
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
}
