package peergos.shared.storage;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.mutable.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Applies a {@link BulkCommit} over the pre-bulk endpoints: batched block/put/bulk posts per writer,
 *  then one setPointers. This is the fallback for a server that doesn't support bulk/commit, so it
 *  keeps the batching, per-block signatures and upload concurrency limit that path has always had.
 */
public class LegacyBulkCommitter implements BulkCommitter {

    private static final int MAX_CONCURRENT_BATCH_UPLOADS = 4;
    private static final int MAX_CBOR_BATCH_SIZE = 1024 * 1024;
    private static final int MAX_CBOR_BLOCKS_PER_BATCH = 1000;

    private final ContentAddressedStorage target;
    private final MutablePointers mutable;
    private final Hasher hasher;

    public LegacyBulkCommitter(ContentAddressedStorage target, MutablePointers mutable, Hasher hasher) {
        this.target = target;
        this.mutable = mutable;
        this.hasher = hasher;
    }

    @Override
    public CompletableFuture<List<Cid>> commit(PublicKeyHash owner,
                                               BulkCommit commit,
                                               Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> signers) {
        if (commit.tid.isPresent())
            return applyWithin(owner, commit, signers, commit.tid.get());
        return target.startTransaction(owner)
                .thenCompose(tid -> applyWithin(owner, commit, signers, tid)
                        .thenCompose(res -> target.closeTransaction(owner, tid).thenApply(x -> res)));
    }

    private CompletableFuture<List<Cid>> applyWithin(PublicKeyHash owner,
                                                     BulkCommit commit,
                                                     Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> signers,
                                                     TransactionId tid) {
        List<Cid> written = new ArrayList<>();
        return Futures.reduceAll(commit.writers, true,
                        (done, w) -> writeBlocks(owner, w, signers.get(w.writer), tid)
                                .thenApply(hashes -> {
                                    written.addAll(hashes);
                                    return done;
                                }),
                        (x, y) -> x && y)
                .thenCompose(x -> {
                    List<SignedPointerUpdate> pointers = commit.writers.stream()
                            .flatMap(w -> w.pointer.stream())
                            .collect(Collectors.toList());
                    if (pointers.isEmpty())
                        return Futures.of(written);
                    return mutable.setPointers(owner, pointers).thenApply(b -> written);
                });
    }

    private CompletableFuture<List<Cid>> writeBlocks(PublicKeyHash owner,
                                                     WriterCommit w,
                                                     SigningPrivateKeyAndPublicHash signer,
                                                     TransactionId tid) {
        if (w.blockCount() == 0)
            return Futures.of(Collections.emptyList());
        List<List<byte[]>> cborBatches = groupBySize(w.cborBlocks);
        List<List<byte[]>> rawBatches = ArrayOps.group(w.rawBlocks, ContentAddressedStorage.MAX_BLOCK_AUTHS);

        AsyncSemaphore semaphore = new AsyncSemaphore(MAX_CONCURRENT_BATCH_UPLOADS);
        List<CompletableFuture<List<Cid>>> futures = Stream.concat(
                        cborBatches.stream().map(b -> new Pair<>(false, b)),
                        rawBatches.stream().map(b -> new Pair<>(true, b)))
                .filter(p -> ! p.right.isEmpty())
                .map(p -> {
                    CompletableFuture<List<Cid>> work = semaphore.acquire()
                            .thenCompose(v -> sign(signer, p.right)
                                    .thenCompose(sigs -> p.left ?
                                            target.putRaw(owner, w.writer, sigs, p.right, tid, x -> {}) :
                                            target.put(owner, w.writer, sigs, p.right, tid)));
                    work.exceptionally(t -> {
                        semaphore.release();
                        return null;
                    });
                    return work.thenApply(r -> {
                        semaphore.release();
                        return r;
                    });
                })
                .collect(Collectors.toList());
        return Futures.combineAllInOrder(futures)
                .thenApply(groups -> groups.stream().flatMap(List::stream).collect(Collectors.toList()));
    }

    private CompletableFuture<List<byte[]>> sign(SigningPrivateKeyAndPublicHash signer, List<byte[]> blocks) {
        return Futures.combineAllInOrder(blocks.stream()
                .map(b -> hasher.sha256(b).thenCompose(h -> signer.secret.signMessage(h)))
                .collect(Collectors.toList()));
    }

    private static List<List<byte[]>> groupBySize(List<byte[]> blocks) {
        List<List<byte[]>> batches = new ArrayList<>();
        int size = 0;
        for (byte[] block : blocks) {
            if (batches.isEmpty() ||
                    size + block.length > MAX_CBOR_BATCH_SIZE ||
                    batches.get(batches.size() - 1).size() >= MAX_CBOR_BLOCKS_PER_BATCH) {
                batches.add(new ArrayList<>());
                size = 0;
            }
            batches.get(batches.size() - 1).add(block);
            size += block.length;
        }
        return batches;
    }
}
