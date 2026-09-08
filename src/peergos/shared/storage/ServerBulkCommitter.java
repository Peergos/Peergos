package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Sends a whole logical write to the server in a single bulk/commit call.
 *
 *  A server that predates the endpoint answers 404, so the first commit to each owner is also the
 *  feature detection: after one of those we use the pre-bulk endpoints for that owner for the rest
 *  of the session. Only an unambiguous "no such call" falls back - anything else could mean the
 *  commit was applied and we must not send it twice.
 */
public class ServerBulkCommitter implements BulkCommitter {

    private final ContentAddressedStorage target;
    private final BulkCommitter fallback;
    private final Hasher hasher;
    private final int maxInlineBytes;
    private final Set<PublicKeyHash> unsupported = new HashSet<>();

    public ServerBulkCommitter(ContentAddressedStorage target, BulkCommitter fallback, Hasher hasher) {
        this(target, fallback, hasher, ContentAddressedStorage.MAX_BULK_COMMIT_SIZE - 64 * 1024);
    }

    public ServerBulkCommitter(ContentAddressedStorage target,
                               BulkCommitter fallback,
                               Hasher hasher,
                               int maxInlineBytes) {
        this.target = target;
        this.fallback = fallback;
        this.hasher = hasher;
        this.maxInlineBytes = maxInlineBytes;
    }

    private synchronized boolean isSupported(PublicKeyHash owner) {
        return ! unsupported.contains(owner);
    }

    private synchronized void markUnsupported(PublicKeyHash owner) {
        unsupported.add(owner);
    }

    @Override
    public CompletableFuture<List<Cid>> commit(PublicKeyHash owner, BulkCommit commit, CommitContext context) {
        if (! isSupported(owner))
            return fallback.commit(owner, commit, context);
        return Futures.asyncExceptionally(
                () -> commit.inlineSize() <= maxInlineBytes ?
                        target.bulkCommit(owner, commit) :
                        splitAndSend(owner, commit, context),
                t -> {
                    if (! isUnsupported(t))
                        return Futures.errored(t);
                    markUnsupported(owner);
                    return fallback.commit(owner, commit, context);
                });
    }

    private static boolean isUnsupported(Throwable t) {
        String msg = Exceptions.getRootCause(t).getMessage();
        if (msg == null)
            return false;
        return msg.contains("Status code: 404")
                || msg.contains("Unimplemented call!")
                || msg.contains("Cannot bulk commit");
    }

    /** A commit too big for one request goes as several, of which only the last carries the pointer
     *  updates. The earlier ones are held by a transaction and stay unreachable until it lands.
     */
    private CompletableFuture<List<Cid>> splitAndSend(PublicKeyHash owner, BulkCommit commit, CommitContext context) {
        return (commit.tid.isPresent() ?
                Futures.of(commit.tid.get()) :
                target.startTransaction(owner))
                .thenCompose(tid -> split(owner, commit, context, tid)
                        .thenCompose(calls -> {
                            List<Cid> written = new ArrayList<>();
                            return Futures.reduceAll(calls, true,
                                            (done, call) -> target.bulkCommit(owner, call).thenApply(cids -> {
                                                written.addAll(cids);
                                                return done;
                                            }),
                                            (x, y) -> x && y)
                                    .thenApply(x -> written);
                        })
                        .thenCompose(written -> commit.tid.isPresent() ?
                                Futures.of(written) :
                                target.closeTransaction(owner, tid).thenApply(x -> written)));
    }

    /** One block of a writer's commit, kept with its hash so it can be ordered and signed for. */
    private static class Block {
        final Cid hash;
        final byte[] data;
        final boolean isRaw;

        Block(Cid hash, byte[] data, boolean isRaw) {
            this.hash = hash;
            this.data = data;
            this.isRaw = isRaw;
        }
    }

    private CompletableFuture<List<BulkCommit>> split(PublicKeyHash owner,
                                                      BulkCommit commit,
                                                      CommitContext context,
                                                      TransactionId tid) {
        return Futures.combineAllInOrder(commit.writers.stream()
                        .map(this::hashBlocks)
                        .collect(Collectors.toList()))
                .thenCompose(perWriter -> {
                    // The final call has to stand on its own: every block in it must be reachable from
                    // the root through blocks in that same call. Ordering each writer's blocks by
                    // distance from its root makes any prefix of that order satisfy this, so the final
                    // call takes the top of each writer's tree and the earlier calls take the rest.
                    List<List<Block>> ordered = new ArrayList<>();
                    for (int i = 0; i < commit.writers.size(); i++)
                        ordered.add(fromRootFirst(perWriter.get(i),
                                context.roots.getOrDefault(commit.writers.get(i).writer, MaybeMultihash.empty())));

                    List<List<Block>> finalCall = new ArrayList<>();
                    List<List<Block>> deferred = new ArrayList<>();
                    int budget = maxInlineBytes;
                    for (List<Block> blocks : ordered) {
                        List<Block> keep = new ArrayList<>();
                        int taken = 0;
                        while (taken < blocks.size() && blocks.get(taken).data.length <= budget) {
                            budget -= blocks.get(taken).data.length;
                            keep.add(blocks.get(taken));
                            taken++;
                        }
                        finalCall.add(keep);
                        deferred.add(new ArrayList<>(blocks.subList(taken, blocks.size())));
                    }

                    return blocksOnlyCalls(commit, context, deferred, tid)
                            .thenApply(calls -> {
                                List<WriterCommit> last = new ArrayList<>();
                                for (int i = 0; i < commit.writers.size(); i++) {
                                    WriterCommit w = commit.writers.get(i);
                                    last.add(new WriterCommit(w.writer,
                                            blocks(finalCall.get(i), false),
                                            blocks(finalCall.get(i), true),
                                            w.preWritten, w.pointer, Optional.empty()));
                                }
                                List<BulkCommit> all = new ArrayList<>(calls);
                                all.add(new BulkCommit(commit.tid, last));
                                return all;
                            });
                });
    }

    /** Pack the blocks that didn't fit into the final call into calls of their own, each writer's share
     *  signed as one list.
     */
    private CompletableFuture<List<BulkCommit>> blocksOnlyCalls(BulkCommit commit,
                                                                CommitContext context,
                                                                List<List<Block>> deferred,
                                                                TransactionId tid) {
        List<List<WriterCommit>> calls = new ArrayList<>();
        List<WriterCommit> current = new ArrayList<>();
        int used = 0;
        List<CompletableFuture<WriterCommit>> pending = new ArrayList<>();
        List<Integer> callOfPending = new ArrayList<>();
        for (int i = 0; i < commit.writers.size(); i++) {
            WriterCommit w = commit.writers.get(i);
            SigningPrivateKeyAndPublicHash signer = context.signers.get(w.writer);
            for (List<Block> group : groupBySize(deferred.get(i))) {
                if (used > 0 && used + size(group) > maxInlineBytes) {
                    calls.add(new ArrayList<>());
                    used = 0;
                }
                if (calls.isEmpty())
                    calls.add(new ArrayList<>());
                used += size(group);
                callOfPending.add(calls.size() - 1);
                pending.add(signBlockList(w.writer, signer, group, context));
            }
        }
        if (pending.isEmpty())
            return Futures.of(Collections.emptyList());
        return Futures.combineAllInOrder(pending)
                .thenApply(writerCommits -> {
                    for (int i = 0; i < writerCommits.size(); i++)
                        calls.get(callOfPending.get(i)).add(writerCommits.get(i));
                    return calls.stream()
                            .filter(c -> ! c.isEmpty())
                            .map(c -> new BulkCommit(Optional.of(tid), c))
                            .collect(Collectors.toList());
                });
    }

    private CompletableFuture<WriterCommit> signBlockList(PublicKeyHash writer,
                                                          SigningPrivateKeyAndPublicHash signer,
                                                          List<Block> group,
                                                          CommitContext context) {
        List<Cid> cbor = group.stream().filter(b -> ! b.isRaw).map(b -> b.hash).collect(Collectors.toList());
        List<Cid> raw = group.stream().filter(b -> b.isRaw).map(b -> b.hash).collect(Collectors.toList());
        List<Cid> inOrder = new ArrayList<>(cbor);
        inOrder.addAll(raw);
        return WriterCommit.blockListPayload(inOrder, context.nextSequence(writer), hasher)
                .thenCompose(payload -> signer.secret.signMessage(payload))
                .thenApply(sig -> new WriterCommit(writer,
                        group.stream().filter(b -> ! b.isRaw).map(b -> b.data).collect(Collectors.toList()),
                        group.stream().filter(b -> b.isRaw).map(b -> b.data).collect(Collectors.toList()),
                        Collections.emptyList(), Optional.empty(), Optional.of(sig)));
    }

    private List<List<Block>> groupBySize(List<Block> blocks) {
        List<List<Block>> groups = new ArrayList<>();
        int used = 0;
        for (Block b : blocks) {
            if (groups.isEmpty() || used + b.data.length > maxInlineBytes) {
                groups.add(new ArrayList<>());
                used = 0;
            }
            groups.get(groups.size() - 1).add(b);
            used += b.data.length;
        }
        return groups;
    }

    private static int size(List<Block> blocks) {
        return blocks.stream().mapToInt(b -> b.data.length).sum();
    }

    private static List<byte[]> blocks(List<Block> blocks, boolean raw) {
        return blocks.stream()
                .filter(b -> b.isRaw == raw)
                .map(b -> b.data)
                .collect(Collectors.toList());
    }

    private CompletableFuture<List<Block>> hashBlocks(WriterCommit w) {
        return Futures.combineAllInOrder(Stream.concat(
                        w.cborBlocks.stream().map(b -> hasher.hash(b, false).thenApply(c -> new Block(c, b, false))),
                        w.rawBlocks.stream().map(b -> hasher.hash(b, true).thenApply(c -> new Block(c, b, true))))
                .collect(Collectors.toList()));
    }

    /** Breadth first from the root, so a block never appears before the block that links to it. */
    private static List<Block> fromRootFirst(List<Block> blocks, MaybeMultihash root) {
        Map<Cid, Block> byHash = new LinkedHashMap<>();
        for (Block b : blocks)
            byHash.put(b.hash, b);
        List<Block> ordered = new ArrayList<>();
        Set<Cid> seen = new HashSet<>();
        Deque<Cid> queue = new ArrayDeque<>();
        root.toOptional().ifPresent(h -> queue.add((Cid) h));
        while (! queue.isEmpty()) {
            Cid next = queue.poll();
            Block block = byHash.get(next);
            if (block == null || ! seen.add(next))
                continue;
            ordered.add(block);
            if (block.isRaw)
                continue;
            for (Multihash link : CborObject.fromByteArray(block.data).links())
                queue.add((Cid) link);
        }
        // Anything the root doesn't reach can only be deferred, and the server will reject it either way
        for (Block b : blocks)
            if (! seen.contains(b.hash))
                ordered.add(b);
        return ordered;
    }
}
