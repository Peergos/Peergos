package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
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
    public CompletableFuture<Multihash> id() {
        return CompletableFuture.completedFuture(new Multihash(Multihash.Type.sha2_256, new byte[32]));
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
        return Paths.get(dir, subdir, h.toBase58());
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        return getRaw(hash)
                .thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signedHashes,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return put(owner, writer, signedHashes, blocks, tid, x -> {}, false);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signedHashes,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressCounter) {
        return put(owner, writer, signedHashes, blocks, tid, progressCounter, true);
    }

    private CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
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

    private CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                   PublicKeyHash writer,
                                                   byte[] signature,
                                                   byte[] block,
                                                   TransactionId tid,
                                                   ProgressConsumer<Long> progressCounter,
                                                   boolean isRaw) {
        byte[] sha256 = Arrays.copyOfRange(signature, signature.length - 32, signature.length);
        Cid cid = buildCid(sha256, isRaw);
        Path toBlock = getPath(cid);
        return baseDir.getOrMkdirs(toBlock.getParent(), network, false, crypto)
                .thenCompose(dir -> dir.uploadOrReplaceFile(toBlock.getFileName().toString(), AsyncReader.build(block),
                        block.length, network, crypto, progressCounter, crypto.random.randomBytes(32)))
                .thenApply(x -> Collections.singletonList(cid));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
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
    public CompletableFuture<Boolean> gc() {
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return getRaw(block).thenApply(b -> b.map(d -> d.length));
    }
}
