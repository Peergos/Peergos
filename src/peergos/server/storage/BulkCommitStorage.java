package peergos.server.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Applies a whole logical write - blocks and pointer updates - for an owner whose home server we are.
 *
 *  This is the local half of {@link ContentAddressedStorage#bulkCommit}; the proxying storage above it
 *  forwards the commit intact to the owner's server when that isn't us.
 */
public class BulkCommitStorage extends DelegatingStorage {

    private final ContentAddressedStorage target;
    private final MutablePointers pointers;

    public BulkCommitStorage(ContentAddressedStorage target, MutablePointers pointers) {
        super(target);
        this.target = target;
        this.pointers = pointers;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
        for (WriterCommit w : commit.writers) {
            if (w.cborBlocks.stream().anyMatch(b -> b.length > ContentAddressedStorage.MAX_BLOCK_SIZE) ||
                    w.rawBlocks.stream().anyMatch(b -> b.length > ContentAddressedStorage.MAX_BLOCK_SIZE))
                throw new IllegalStateException("Block too big!");
        }
        return withTransaction(owner, commit.tid, tid -> writeBlocks(owner, commit, tid)
                .thenCompose(written -> {
                    List<SignedPointerUpdate> updates = commit.writers.stream()
                            .flatMap(w -> w.pointer.stream())
                            .collect(Collectors.toList());
                    if (updates.isEmpty())
                        return Futures.of(written);
                    return pointers.setPointers(owner, updates).thenApply(b -> written);
                }));
    }

    private CompletableFuture<List<Cid>> withTransaction(PublicKeyHash owner,
                                                         Optional<TransactionId> supplied,
                                                         java.util.function.Function<TransactionId, CompletableFuture<List<Cid>>> body) {
        if (supplied.isPresent())
            return body.apply(supplied.get())
                    .thenCompose(res -> target.closeTransaction(owner, supplied.get()).thenApply(x -> res));
        return target.startTransaction(owner)
                .thenCompose(tid -> body.apply(tid)
                        .thenCompose(res -> target.closeTransaction(owner, tid).thenApply(x -> res)));
    }

    /** The blocks carry no signature of their own: the pointer update signs a root that names them all,
     *  and reachability from that root is checked before we get here.
     */
    private CompletableFuture<List<Cid>> writeBlocks(PublicKeyHash owner, BulkCommit commit, TransactionId tid) {
        List<Cid> written = new ArrayList<>();
        return Futures.reduceAll(commit.writers, true,
                        (done, w) -> writeBlocks(owner, w, tid).thenApply(cids -> {
                            written.addAll(cids);
                            return done;
                        }),
                        (x, y) -> x && y)
                .thenApply(x -> written);
    }

    private CompletableFuture<List<Cid>> writeBlocks(PublicKeyHash owner, WriterCommit w, TransactionId tid) {
        List<byte[]> unsigned = unsigned(w.cborBlocks.size());
        return (w.cborBlocks.isEmpty() ?
                Futures.of(Collections.<Cid>emptyList()) :
                target.put(owner, w.writer, unsigned, w.cborBlocks, tid))
                .thenCompose(cbor -> (w.rawBlocks.isEmpty() ?
                        Futures.of(Collections.<Cid>emptyList()) :
                        target.putRaw(owner, w.writer, unsigned(w.rawBlocks.size()), w.rawBlocks, tid, x -> {}))
                        .thenApply(raw -> {
                            List<Cid> all = new ArrayList<>(cbor);
                            all.addAll(raw);
                            return all;
                        }));
    }

    private static List<byte[]> unsigned(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new byte[0])
                .collect(Collectors.toList());
    }
}
