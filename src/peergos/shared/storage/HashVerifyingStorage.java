package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class HashVerifyingStorage extends DelegatingStorage {

    private final ContentAddressedStorage source;
    private final Hasher hasher;

    public HashVerifyingStorage(ContentAddressedStorage source, Hasher hasher) {
        super(source);
        this.source = source;
        this.hasher = hasher;
    }

    private <T> CompletableFuture<T> verify(byte[] data, Multihash claimed, Supplier<T> result) {
        switch (claimed.type) {
            case sha2_256:
                return hasher.sha256(data)
                        .thenApply(hash -> {
                            Multihash computed = new Multihash(Multihash.Type.sha2_256, hash);
                            if (claimed instanceof Cid)
                                computed = Cid.build(((Cid) claimed).version, ((Cid) claimed).codec, computed);

                            if (computed.equals(claimed))
                                return result.get();

                            throw new IllegalStateException("Incorrect hash! Are you under attack? Expected: " + claimed + " actual: " + computed);
                        });
            case id:
                if (Arrays.equals(data, claimed.getHash()))
                    return Futures.of(result.get());
                throw new IllegalStateException("Incorrect identity hash! This shouldn't ever  happen.");
            default: throw new IllegalStateException("Unimplemented hash algorithm: " + claimed.type);
        }
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return source.blockStoreProperties();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new HashVerifyingStorage(source.directToOrigin(), hasher);
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return source.put(owner, writer, signedHashes, blocks, tid)
                .thenCompose(hashes -> Futures.combineAllInOrder(hashes.stream()
                        .map(h -> verify(blocks.get(hashes.indexOf(h)), h, () -> h))
                        .collect(Collectors.toList())));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return source.get(owner, hash, bat)
                .thenCompose(cborOpt -> cborOpt.map(cbor -> verify(cbor.toByteArray(), hash, () -> cbor)
                        .thenApply(Optional::of))
                        .orElseGet(() -> Futures.of(Optional.empty())));
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return source.putRaw(owner, writer, signatures, blocks, tid, progressConsumer)
                .thenCompose(hashes -> Futures.combineAllInOrder(hashes.stream()
                        .map(h -> verify(blocks.get(hashes.indexOf(h)), h, () -> h))
                        .collect(Collectors.toList())));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return source.getRaw(owner, hash, bat)
                .thenCompose(arrOpt -> arrOpt.map(bytes -> verify(bytes, hash, () -> bytes)
                        .thenApply(Optional::of))
                        .orElseGet(() -> Futures.of(Optional.empty())));
    }
}
