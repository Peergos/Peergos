package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class WriteFilter implements ContentAddressedStorage {

    private final ContentAddressedStorage dht;
    private final BiFunction<PublicKeyHash, Integer, Boolean> keyFilter;

    public WriteFilter(ContentAddressedStorage dht, BiFunction<PublicKeyHash, Integer, Boolean> keyFilter) {
        this.dht = dht;
        this.keyFilter = keyFilter;
    }

    @Override
    public CompletableFuture<Multihash> id() {
        return dht.id();
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return dht.startTransaction(owner);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return dht.closeTransaction(owner, tid);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash object) {
        return dht.get(object);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash object) {
        return dht.getRaw(object);
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        return dht.getLinks(root);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return dht.getSize(block);
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signatures,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        if (! keyFilter.apply(writer, blocks.stream().mapToInt(x -> x.length).sum()))
            throw new IllegalStateException("Key not allowed to write to this server: " + writer);
        return dht.put(owner, writer, signatures, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid) {
        if (! keyFilter.apply(writer, blocks.stream().mapToInt(x -> x.length).sum()))
            throw new IllegalStateException("Key not allowed to write to this server: " + writer);
        return dht.putRaw(owner, writer, signatures, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return dht.pinUpdate(owner, existing, updated);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h) {
        return dht.recursivePin(owner, h);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h) {
        return dht.recursiveUnpin(owner, h);
    }
}
