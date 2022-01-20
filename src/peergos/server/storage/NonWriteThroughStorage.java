package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class NonWriteThroughStorage implements ContentAddressedStorage {
    private final ContentAddressedStorage source;
    private final ContentAddressedStorage modifications;

    public NonWriteThroughStorage(ContentAddressedStorage source, Hasher hasher) {
        this.source = source;
        this.modifications = new RAMStorage(hasher);
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return source.id();
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return modifications.startTransaction(owner);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return modifications.closeTransaction(owner, tid);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        return modifications.getChampLookup(owner, root, champKey, bat);
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return modifications.put(owner, writer, signedHashes, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return modifications.putRaw(owner, writer, signatures, blocks, tid, progressConsumer);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid object, Optional<BatWithId> bat) {
        try {
            Optional<byte[]> modified = modifications.getRaw(object, bat).get();
            if ( modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.getRaw(object, bat);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        try {
            Optional<CborObject> modified = modifications.get(hash, bat).get();
            if ( modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.get(hash, bat);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        try {
            Optional<CborObject> modified = modifications.get((Cid)block, Optional.empty()).get();
            if (modified.isPresent())
                return CompletableFuture.completedFuture(modified.map(cbor -> cbor.toByteArray().length));
            return source.getSize(block);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
