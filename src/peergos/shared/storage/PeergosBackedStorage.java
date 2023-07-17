package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** This stores blocks as files inside a directory in peergos (encrypted)
 *
 * A depth of two subdirectories are used with 256 children at each level
 *
 */
public class PeergosBackedStorage implements ContentAddressedStorage {

    private FileWrapper baseDir;
    private final Crypto crypto;
    private final NetworkAccess network;

    public PeergosBackedStorage(FileWrapper baseDir, Crypto crypto, NetworkAccess network) {
        this.baseDir = baseDir;
        this.crypto = crypto;
        this.network = network;
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
        return CompletableFuture.completedFuture(tid);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return Futures.of(true);
    }

    private Path getPath(Multihash h) {
        byte[] hash = h.getHash();
        // use final bytes which are random rather than start
        String dir = ArrayOps.byteToHex(hash[hash.length - 1]);
        String subdir = ArrayOps.byteToHex(hash[hash.length - 2]);
        return PathUtil.get(dir, subdir, h.toBase58());
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        return getRaw(hash, bat)
                .thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return put(owner, writer, signedHashes, blocks, tid, x -> {}, false);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signedHashes,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressCounter) {
        return put(owner, writer, signedHashes, blocks, tid, progressCounter, true);
    }

    private CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             List<byte[]> signatures,
                                             List<byte[]> blocks,
                                             TransactionId tid,
                                             ProgressConsumer<Long> progressCounter,
                                             boolean isRaw) {
        List<Pair<byte[], byte[]>> paired = IntStream.range(0, blocks.size())
                .mapToObj(i -> new Pair<>(signatures.get(i), blocks.get(i)))
                .collect(Collectors.toList());
        return Futures.reduceAll(paired, Collections.emptyList(),
                (acc, p) -> put(owner, writer, p.left, p.right, tid, progressCounter, isRaw)
                        .thenApply(res -> Stream.concat(acc.stream(), res.stream()).collect(Collectors.toList())),
                (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList()));
    }

    private CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             byte[] signature,
                                             byte[] block,
                                             TransactionId tid,
                                             ProgressConsumer<Long> progressCounter,
                                             boolean isRaw) {
        byte[] sha256 = Arrays.copyOfRange(signature, signature.length - 32, signature.length);
        Cid cid = buildCid(sha256, isRaw);
        Path toBlock = getPath(cid);
        return baseDir.getOrMkdirs(toBlock.getParent(), network, false, baseDir.mirrorBatId(), crypto)
                .thenCompose(dir -> dir.uploadOrReplaceFile(toBlock.getFileName().toString(), AsyncReader.build(block),
                        block.length, network, crypto, progressCounter))
                .thenApply(x -> Collections.singletonList(cid));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        return baseDir.getDescendentByPath(getPath(hash).toString(), crypto.hasher, network)
                .thenCompose(fopt -> {
                    if (fopt.isEmpty())
                        return Futures.of(Optional.empty());
                    FileWrapper f = fopt.get();
                    return f.getInputStream(network, crypto, x -> {})
                            .thenCompose(reader -> Serialize.readFully(reader, f.getSize()))
                            .thenApply(Optional::of);
                });
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        return Futures.of(Collections.emptyList());
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return getRaw((Cid)block, Optional.empty()).thenApply(b -> b.map(d -> d.length));
    }
}
