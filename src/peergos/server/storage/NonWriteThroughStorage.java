package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;

public class NonWriteThroughStorage implements ContentAddressedStorage {
    private final ContentAddressedStorage source;
    private final ContentAddressedStorage modifications;

    public NonWriteThroughStorage(ContentAddressedStorage source) {
        this.source = source;
        this.modifications = new RAMStorage();
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks) {
        return modifications.put(writer, signatures, blocks);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks) {
        return modifications.putRaw(writer, signatures, blocks);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash object) {
        try {
            Optional<byte[]> modified = modifications.getRaw(object).get();
            if ( modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.getRaw(object);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        try {
            Optional<CborObject> modified = modifications.get(hash).get();
            if ( modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.get(hash);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(Multihash h) {
        return modifications.recursivePin(h);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h) {
        return modifications.recursiveUnpin(h);
    }

    @Override
    public CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated) {
        return modifications.pinUpdate(existing, updated);
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        try {
            Optional<CborObject> modified = modifications.get(root).get();
            if (modified.isPresent())
                return CompletableFuture.completedFuture(modified.get().links());
            return source.getLinks(root);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        try {
            Optional<CborObject> modified = modifications.get(block).get();
            if (modified.isPresent())
                return CompletableFuture.completedFuture(modified.map(cbor -> cbor.toByteArray().length));
            return source.getSize(block);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
