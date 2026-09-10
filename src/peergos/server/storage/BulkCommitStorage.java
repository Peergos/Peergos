package peergos.server.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** Applies a whole logical write - blocks and pointer updates - for an owner whose home server we are.
 *
 *  This is the local half of {@link ContentAddressedStorage#bulkCommit}; the proxying storage above it
 *  forwards the commit intact to the owner's server when that isn't us.
 *
 *  The blocks carry no signature of their own. What authorises them is that the pointer update signs a
 *  root which names them, transitively, by hash - so this class will not write a block until it has
 *  checked that the signed root really does reach it, and that the commit leaves no dangling link.
 */
public class BulkCommitStorage extends DelegatingStorage {

    /** Whether a commit may be applied, given the bytes it writes and, if that alone would be
     *  refused, the net change in stored bytes its pointer updates will cause. */
    public interface CommitQuota {
        boolean allow(PublicKeyHash owner, PublicKeyHash writer, int written, Supplier<Long> delta);
    }

    private final ContentAddressedStorage target;
    private final DeletableContentAddressedStorage unfiltered;
    private final MutablePointers pointers;
    private final Hasher hasher;
    private final BiFunction<PublicKeyHash, PublicKeyHash, Boolean> registerWriter;
    private final CommitQuota quota;

    public BulkCommitStorage(ContentAddressedStorage target,
                             DeletableContentAddressedStorage unfiltered,
                             MutablePointers pointers,
                             Hasher hasher,
                             BiFunction<PublicKeyHash, PublicKeyHash, Boolean> registerWriter,
                             CommitQuota quota) {
        super(target);
        this.target = target;
        this.unfiltered = unfiltered;
        this.pointers = pointers;
        this.hasher = hasher;
        this.registerWriter = registerWriter;
        this.quota = quota;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
        for (WriterCommit w : commit.writers) {
            if (w.pointer.isEmpty()) {
                // Nothing signs a root that names these blocks yet, so the writer signs the list itself,
                // and they stay unreachable until a later call's pointer update makes them not.
                if (w.blockListSignature.isEmpty())
                    throw new IllegalStateException("A bulk commit with no pointer update is unauthenticated!");
                if (commit.tid.isEmpty())
                    throw new IllegalStateException("Blocks committed before the pointer that names them need a transaction!");
            }
            if (Stream.concat(w.cborBlocks.stream(), w.rawBlocks.stream())
                    .anyMatch(b -> b.length > ContentAddressedStorage.MAX_BLOCK_SIZE))
                throw new IllegalStateException("Block too big!");
        }
        int named = commit.blockCount() + commit.writers.stream().mapToInt(w -> w.preWritten.size()).sum();
        if (named > ContentAddressedStorage.MAX_BULK_COMMIT_BLOCKS)
            throw new IllegalStateException("Too many blocks in one commit: " + named);
        return hashBlocks(commit)
                .thenCompose(hashes -> updates(owner, commit)
                        .thenCompose(updates -> verifyBlockLists(owner, commit, hashes)
                                .thenCompose(x -> verify(owner, commit, hashes, updates))
                                .thenCompose(inCall -> registerNewWriters(owner, commit, updates, inCall)
                                        .thenApply(y -> inCall))
                                .thenCompose(inCall -> withTransaction(owner, commit.tid, commit.hasPointerUpdate(),
                                        // the transaction has to outlive the pointer update, or the blocks
                                        // it holds are collectable in the window before they are reachable
                                        tid -> writeBlocks(owner, commit, updates, inCall, tid)
                                                .thenCompose(written -> {
                                                    List<SignedPointerUpdate> signed = commit.writers.stream()
                                                            .flatMap(w -> w.pointer.stream())
                                                            .collect(Collectors.toList());
                                                    if (signed.isEmpty())
                                                        return Futures.of(written);
                                                    return pointers.setPointers(owner, signed)
                                                            .thenApply(b -> written);
                                                })))));
    }

    /** Apply a batch signed block write for an owner whose home server we are.
     *
     *  The blocks carry no signature each; what authorises them is one signature over the owner and
     *  the ordered hashes, so that has to be checked here rather than anywhere the batch has been
     *  taken apart.
     */
    @Override
    public CompletableFuture<List<Cid>> putBatch(PublicKeyHash owner,
                                                 PublicKeyHash writer,
                                                 BlockWriteBatch batch,
                                                 boolean isRaw,
                                                 TransactionId tid) {
        if (batch.blocks.size() > ContentAddressedStorage.MAX_BULK_COMMIT_BLOCKS)
            throw new IllegalStateException("Too many blocks in one write: " + batch.blocks.size());
        if (batch.blocks.stream().anyMatch(b -> b.length > ContentAddressedStorage.MAX_BLOCK_SIZE))
            throw new IllegalStateException("Block too big!");
        return Futures.combineAllInOrder(batch.blocks.stream()
                        .map(b -> hasher.hash(b, isRaw))
                        .collect(Collectors.toList()))
                .thenCompose(hashes -> BlockWriteAuth.payload(owner, hashes, hasher)
                        .thenCompose(expected -> writerKey(owner, writer)
                                .thenCompose(key -> key.unsignMessage(batch.signature)
                                        .thenApply(signed -> {
                                            if (! Arrays.equals(signed, expected))
                                                throw new IllegalStateException("Invalid signature for block write batch!");
                                            return true;
                                        }))))
                .thenCompose(x -> {
                    List<byte[]> unsigned = unsigned(batch.blocks.size());
                    return isRaw ?
                            target.putRaw(owner, writer, unsigned, batch.blocks, tid, y -> {}) :
                            target.put(owner, writer, unsigned, batch.blocks, tid);
                });
    }

    /** The hash of every block travelling inline, in commit order: each writer's cbor blocks then its raw ones. */
    private CompletableFuture<List<List<Cid>>> hashBlocks(BulkCommit commit) {
        return Futures.combineAllInOrder(commit.writers.stream()
                .map(w -> Futures.combineAllInOrder(Stream.concat(
                                w.cborBlocks.stream().map(b -> hasher.hash(b, false)),
                                w.rawBlocks.stream().map(b -> hasher.hash(b, true)))
                        .collect(Collectors.toList())))
                .collect(Collectors.toList()));
    }

    /** Check that the signed roots reach every block in the call, and that the call leaves no link dangling.
     *
     * @return the cbor blocks in the call, by hash
     */
    private CompletableFuture<Map<Cid, byte[]>> verify(PublicKeyHash owner,
                                                       BulkCommit commit,
                                                       List<List<Cid>> hashes,
                                                       List<Optional<PointerUpdate>> updates) {
        Map<Cid, byte[]> cborInCall = new HashMap<>();
        Set<Cid> mustBeReachable = new HashSet<>();
        Set<Cid> declared = new HashSet<>();
        for (int i = 0; i < commit.writers.size(); i++) {
            WriterCommit w = commit.writers.get(i);
            List<Cid> cids = hashes.get(i);
            for (int j = 0; j < w.cborBlocks.size(); j++)
                cborInCall.put(cids.get(j), w.cborBlocks.get(j));
            // Blocks sent ahead of the pointer that will name them have nothing to be reachable from yet
            if (w.pointer.isPresent())
                mustBeReachable.addAll(cids);
            declared.addAll(cids);
            declared.addAll(w.preWritten);
        }

        List<Cid> roots = updates.stream()
                .flatMap(Optional::stream)
                .flatMap(u -> u.updated.toOptional().stream())
                .map(h -> (Cid) h)
                .collect(Collectors.toList());
        Set<Cid> external = new HashSet<>(commit.writers.stream()
                .flatMap(w -> w.preWritten.stream())
                .collect(Collectors.toSet()));

        // Every block in the call must be reachable from a root this call signs
        Set<Cid> reachable = new HashSet<>();
        for (Cid root : roots) {
            if (cborInCall.containsKey(root))
                markReachable(root, reachable, cborInCall);
            else
                external.add(root);
        }
        Optional<Cid> orphan = mustBeReachable.stream()
                .filter(c -> ! reachable.contains(c))
                .findFirst();
        if (orphan.isPresent())
            throw new IllegalStateException("Block in a bulk commit is not reachable from the new root: " + orphan.get());

        // Every link out of a block this call is making reachable must resolve, here or in what we hold
        for (Map.Entry<Cid, byte[]> e : cborInCall.entrySet()) {
            if (! mustBeReachable.contains(e.getKey()))
                continue;
            for (Multihash link : CborObject.fromByteArray(e.getValue()).links()) {
                Cid c = (Cid) link;
                if (c.isIdentity() || declared.contains(c))
                    continue;
                external.add(c);
            }
        }

        return Futures.combineAllInOrder(external.stream()
                        .map(c -> target.getSize(owner, c)
                                .thenApply(size -> new Pair<>(c, size.isPresent())))
                        .collect(Collectors.toList()))
                .thenApply(results -> {
                    Optional<Cid> missing = results.stream()
                            .filter(p -> ! p.right)
                            .map(p -> p.left)
                            .findFirst();
                    if (missing.isPresent())
                        throw new IllegalStateException("Bulk commit references a block we don't have: " + missing.get());
                    return cborInCall;
                });
    }

    /** A writer with no previous pointer value is being created by this commit, and the server won't accept
     *  its blocks until it knows the owner owns it. The proof is in the commit: an existing writer's newly
     *  signed WriterData names it as an owned key - which is what lets a parent and the child it creates
     *  commit together, rather than the child's blocks having to wait for the parent's pointer to land.
     */
    private CompletableFuture<Boolean> registerNewWriters(PublicKeyHash owner,
                                                          BulkCommit commit,
                                                          List<Optional<PointerUpdate>> updates,
                                                          Map<Cid, byte[]> cborInCall) {
        List<PublicKeyHash> newWriters = new ArrayList<>();
        for (int i = 0; i < commit.writers.size(); i++)
            if (updates.get(i).map(u -> ! u.original.isPresent()).orElse(false))
                newWriters.add(commit.writers.get(i).writer);
        if (newWriters.isEmpty())
            return Futures.of(true);

        ContentAddressedStorage withCallBlocks = overlay(cborInCall);
        // Only a writer that isn't itself being created here can vouch for one that is, so widen the set
        // of vouchers a step at a time until it stops growing.
        Set<PublicKeyHash> authorised = new HashSet<>();
        authorised.add(owner);
        for (int i = 0; i < commit.writers.size(); i++)
            if (updates.get(i).map(u -> u.original.isPresent()).orElse(true))
                authorised.add(commit.writers.get(i).writer);
        List<PublicKeyHash> remaining = new ArrayList<>(newWriters);
        List<PublicKeyHash> provenHere = new ArrayList<>();
        while (! remaining.isEmpty()) {
            Set<PublicKeyHash> owned = new HashSet<>();
            for (int i = 0; i < commit.writers.size(); i++) {
                WriterCommit w = commit.writers.get(i);
                if (! authorised.contains(w.writer))
                    continue;
                // the same proof the usage store's self heal walks: the owned key champ and the named
                // owned keys, each carrying a signature by the writer claiming to own it
                owned.addAll(DeletableContentAddressedStorage.getDirectOwnedKeys(owner, w.writer,
                        updates.get(i).map(u -> u.updated).orElse(MaybeMultihash.empty()),
                        (h, seq) -> ContentAddressedStorage.getWriterData(owner, h, seq, withCallBlocks),
                        withCallBlocks, hasher).join());
            }
            List<PublicKeyHash> vouched = remaining.stream()
                    .filter(owned::contains)
                    .collect(Collectors.toList());
            if (vouched.isEmpty())
                break;
            authorised.addAll(vouched);
            provenHere.addAll(vouched);
            remaining.removeAll(vouched);
        }
        // A writer this call doesn't vouch for may still be provable from what is already committed,
        // which is exactly what the quota check's own self heal does, so leave those to it.
        for (PublicKeyHash newWriter : provenHere)
            registerWriter.apply(owner, newWriter);
        return Futures.of(true);
    }

    /** A view of the store with this call's blocks in it, for reading a graph the call is only proposing. */
    private ContentAddressedStorage overlay(Map<Cid, byte[]> cborInCall) {
        ContentAddressedStorage delegate = target;
        return new DelegatingStorage(delegate) {
            @Override
            public ContentAddressedStorage directToOrigin() {
                return this;
            }

            @Override
            public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
                byte[] block = cborInCall.get(hash);
                if (block != null)
                    return Futures.of(Optional.of(block));
                return delegate.getRaw(owner, hash, bat);
            }

            @Override
            public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
                if (hash.isIdentity())
                    return Futures.of(Optional.of(CborObject.fromByteArray(hash.getHash())));
                return getRaw(owner, hash, bat).thenApply(opt -> opt.map(CborObject::fromByteArray));
            }
        };
    }

    /** The pointer update each writer is proposing, which also checks the signature we are relying on. */
    private CompletableFuture<List<Optional<PointerUpdate>>> updates(PublicKeyHash owner, BulkCommit commit) {
        return Futures.combineAllInOrder(commit.writers.stream()
                .map(w -> w.pointer.isEmpty() ?
                        Futures.of(Optional.<PointerUpdate>empty()) :
                        writerKey(owner, w.writer)
                                .thenCompose(key -> key.unsignMessage(w.pointer.get().signed))
                                .thenApply(signed -> Optional.of(PointerUpdate.fromCbor(CborObject.fromByteArray(signed)))))
                .collect(Collectors.toList()));
    }

    private CompletableFuture<PublicSigningKey> writerKey(PublicKeyHash owner, PublicKeyHash writer) {
        return target.getSigningKey(owner, writer)
                .thenApply(keyOpt -> {
                    if (keyOpt.isEmpty())
                        throw new IllegalStateException("Couldn't retrieve writer key " + writer);
                    return keyOpt.get();
                });
    }

    /** A writer sending blocks with no pointer update signs the ordered list of their hashes, bound to
     *  the sequence its pointer is heading for so the list can't be replayed against a later state.
     */
    private CompletableFuture<Boolean> verifyBlockLists(PublicKeyHash owner, BulkCommit commit, List<List<Cid>> hashes) {
        List<Integer> blocksOnly = IntStream.range(0, commit.writers.size())
                .filter(i -> commit.writers.get(i).pointer.isEmpty())
                .boxed()
                .collect(Collectors.toList());
        if (blocksOnly.isEmpty())
            return Futures.of(true);
        return Futures.combineAllInOrder(blocksOnly.stream()
                        .map(i -> {
                            WriterCommit w = commit.writers.get(i);
                            return pointers.getPointerTarget(owner, w.writer, target)
                                    .thenCompose(current -> WriterCommit.blockListPayload(hashes.get(i),
                                            PointerUpdate.increment(current.sequence), hasher))
                                    .thenCompose(expected -> writerKey(owner, w.writer)
                                            .thenCompose(key -> key.unsignMessage(w.blockListSignature.get())
                                                    .thenApply(signed -> {
                                                        if (! Arrays.equals(signed, expected))
                                                            throw new IllegalStateException("Invalid block list signature for " + w.writer);
                                                        return true;
                                                    })));
                        })
                        .collect(Collectors.toList()))
                .thenApply(x -> true);
    }

    private static void markReachable(Cid current, Set<Cid> reachable, Map<Cid, byte[]> cborInCall) {
        if (! reachable.add(current))
            return;
        byte[] block = cborInCall.get(current);
        if (block == null)
            return;
        for (Multihash link : CborObject.fromByteArray(block).links()) {
            Cid c = (Cid) link;
            if (! c.isIdentity())
                markReachable(c, reachable, cborInCall);
        }
    }

    /** A commit that carries pointer updates is the last call under its transaction: once those land
     *  its blocks are reachable and the transaction has done its job, so closing it here saves the
     *  sender a round trip. A blocks-only call is not the last, so its transaction stays open, and a
     *  commit whose pointers fail leaves it open too - it may still be retried under it.
     */
    private CompletableFuture<List<Cid>> withTransaction(PublicKeyHash owner,
                                                         Optional<TransactionId> supplied,
                                                         boolean isLastCall,
                                                         Function<TransactionId, CompletableFuture<List<Cid>>> body) {
        if (supplied.isPresent() && ! isLastCall)
            return body.apply(supplied.get());
        TransactionId ours = supplied.orElse(null);
        return (ours != null ? Futures.of(ours) : target.startTransaction(owner))
                .thenCompose(tid -> body.apply(tid)
                        .thenCompose(res -> target.closeTransaction(owner, tid).thenApply(x -> res)));
    }

    private CompletableFuture<List<Cid>> writeBlocks(PublicKeyHash owner,
                                                     BulkCommit commit,
                                                     List<Optional<PointerUpdate>> updates,
                                                     Map<Cid, byte[]> cborInCall,
                                                     TransactionId tid) {
        List<Cid> written = new ArrayList<>();
        List<Integer> indices = IntStream.range(0, commit.writers.size()).boxed().collect(Collectors.toList());
        return Futures.reduceAll(indices, true,
                        (done, i) -> {
                            WriterCommit w = commit.writers.get(i);
                            // Committing a delete is still a write, so charging only the bytes it adds
                            // leaves a user who is over quota unable to get back under. Charge it
                            // against what the commit will actually leave stored.
                            int size = w.inlineSize();
                            if (! quota.allow(owner, w.writer, size, () -> deltaFor(owner, updates.get(i), cborInCall)))
                                throw new IllegalStateException("Key not allowed to write to this server: " + w.writer);
                            return writeBlocks(owner, w, tid).thenApply(cids -> {
                                written.addAll(cids);
                                return done;
                            });
                        },
                        (x, y) -> x && y)
                .thenApply(x -> written);
    }

    /** The change in stored bytes this writer's pointer update will cause, measured against what we
     *  already hold. The new root is only in the call at this point, so the diff runs over a view of
     *  the store that includes the call's own blocks.
     */
    private long deltaFor(PublicKeyHash owner, Optional<PointerUpdate> update, Map<Cid, byte[]> cborInCall) {
        if (update.isEmpty())
            return 0;
        PointerUpdate u = update.get();
        Optional<Cid> before = u.original.toOptional().map(c -> (Cid) c);
        Optional<Cid> after = u.updated.toOptional().map(c -> (Cid) c);
        DeletableContentAddressedStorage view = withCallBlocks(cborInCall);
        if (after.isEmpty())
            // the whole of this writer's tree goes away
            return before.map(b -> - view.getRecursiveBlockSize(owner, b,
                    Collections.singletonList(unfiltered.id().join())).join()).orElse(0L);
        return view.getChangeInContainedSize(owner, before, after.get()).join();
    }

    /** The block store as it will look once this commit's blocks are written, for measuring against. */
    private DeletableContentAddressedStorage withCallBlocks(Map<Cid, byte[]> cborInCall) {
        return new DelegatingDeletableStorage(unfiltered) {
            @Override
            public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
                byte[] raw = cborInCall.get(block);
                if (raw != null)
                    return Futures.of(BlockMetadataStore.extractMetadata(block, raw));
                return unfiltered.getBlockMetadata(owner, block);
            }
        };
    }

    private CompletableFuture<List<Cid>> writeBlocks(PublicKeyHash owner, WriterCommit w, TransactionId tid) {
        // the quota decision was made above against the commit's net effect, so write below the filter
        return (w.cborBlocks.isEmpty() ?
                Futures.of(Collections.<Cid>emptyList()) :
                unfiltered.put(owner, w.writer, unsigned(w.cborBlocks.size()), w.cborBlocks, tid))
                .thenCompose(cbor -> (w.rawBlocks.isEmpty() ?
                        Futures.of(Collections.<Cid>emptyList()) :
                        unfiltered.putRaw(owner, w.writer, unsigned(w.rawBlocks.size()), w.rawBlocks, tid, x -> {}))
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
