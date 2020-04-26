package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multibase.binary.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class DirectS3BlockStore implements ContentAddressedStorage {

    private final boolean directWrites, publicReads;
    private final Optional<String> baseUrl;
    private final HttpPoster direct;
    private final ContentAddressedStorage fallback;

    public DirectS3BlockStore(BlockStoreProperties blockStoreProperties, HttpPoster direct, ContentAddressedStorage fallback) {
        this.directWrites = blockStoreProperties.directWrites;
        this.publicReads = blockStoreProperties.publicReads;
        this.baseUrl = blockStoreProperties.baseUrl;
        this.direct = direct;
        this.fallback = fallback;
    }

    public static String hashToKey(Multihash hash) {
        // To be compatible with IPFS we use the same scheme here, the cid bytes encoded as uppercase base32
        String padded = new Base32().encodeAsString(hash.toBytes());
        int padStart = padded.indexOf("=");
        return padStart > 0 ? padded.substring(0, padStart) : padded;
    }

    public static Multihash keyToHash(String keyFileName) {
        // To be compatible with IPFS we use the same scheme here, the cid bytes encoded as uppercase base32
        byte[] decoded = new Base32().decode(keyFileName);
        return Cid.cast(decoded);
    }

    @Override
    public CompletableFuture<Multihash> id() {
        return fallback.id();
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return fallback.startTransaction(owner);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return fallback.closeTransaction(owner, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signatures,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        if (directWrites) {
            CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
            fallback.authWrites(owner, writer, signatures, blocks.stream().map(x -> x.length).collect(Collectors.toList()), false, tid)
                    .thenCompose(preAuthed -> {
                        List<CompletableFuture<Multihash>> futures = new ArrayList<>();
                        for (int i=0; i < blocks.size(); i++) {
                            PresignedUrl url = preAuthed.get(i);
                            Multihash targetName = keyToHash(url.base.substring(url.base.lastIndexOf("/") + 1));
                            futures.add(direct.put(url.base, blocks.get(i), url.fields)
                                    .thenApply(x -> targetName));
                        }
                        return Futures.combineAllInOrder(futures);
                    }).thenApply(res::complete)
                    .exceptionally(res::completeExceptionally);
            return res;
        }
        return fallback.put(owner, writer, signatures, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid) {
        if (directWrites) {
            CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
            fallback.authWrites(owner, writer, signatures, blocks.stream().map(x -> x.length).collect(Collectors.toList()), true, tid)
                    .thenCompose(preAuthed -> {
                        List<CompletableFuture<Multihash>> futures = new ArrayList<>();
                        for (int i=0; i < blocks.size(); i++) {
                            PresignedUrl url = preAuthed.get(i);
                            Multihash targetName = keyToHash(url.base.substring(url.base.lastIndexOf("/") + 1));
                            futures.add(direct.put(url.base, blocks.get(i), url.fields)
                                    .thenApply(x -> targetName));
                        }
                        return Futures.combineAllInOrder(futures);
                    }).thenApply(res::complete)
                    .exceptionally(res::completeExceptionally);
            return res;
        }
        return fallback.putRaw(owner, writer, signatures, blocks, tid);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        return getRaw(hash).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
        if (publicReads) {
            CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
            direct.get(baseUrl.get() + hashToKey(hash))
                    .thenAccept(raw -> res.complete(Optional.of(raw)))
                    .exceptionally(t -> {
                        fallback.getRaw(hash)
                                .thenAccept(fallbackRes -> res.complete(fallbackRes))
                                .exceptionally(e -> {
                                    res.completeExceptionally(e);
                                    return null;
                                });
                        return null;
                    });
            return res;
        }
        return fallback.getRaw(hash);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return fallback.getSize(block);
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return Futures.of(Collections.singletonList(updated));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash) {
        return Futures.of(Collections.singletonList(hash));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash) {
        return Futures.of(Collections.singletonList(hash));
    }

    @Override
    public CompletableFuture<Boolean> gc() {
        return Futures.errored(new IllegalStateException("S3 doesn't implement GC!"));
    }
}
