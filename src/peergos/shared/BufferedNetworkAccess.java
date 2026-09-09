package peergos.shared;

import peergos.shared.cbor.CborObject;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.ChampUtil;
import peergos.shared.hamt.ChampWrapper;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** This will buffer block writes, and mutable pointer updates and commit in bulk
 *
 */
public class BufferedNetworkAccess extends NetworkAccess {

    public interface Flusher {
        CompletableFuture<Snapshot> commit(PublicKeyHash owner, Snapshot v, Supplier<Boolean> commitWatcher);
    }

    private final BufferedStorage blockBuffer;
    private final BufferedPointers pointerBuffer;
    private final BulkCommitter bulkCommitter;
    private final int bufferSize;
    private boolean safeToCommit = true;

    public BufferedNetworkAccess(BufferedStorage blockBuffer,
                                 BufferedPointers mutableBuffer,
                                 int bufferSize,
                                 CoreNode coreNode,
                                 Account account,
                                 SocialNetwork social,
                                 MutablePointers unbufferedMutable,
                                 BatCave batCave,
                                 Optional<EncryptedBatCache> batCache,
                                 MutableTree tree,
                                 WriteSynchronizer synchronizer,
                                 InstanceAdmin instanceAdmin,
                                 SpaceUsage spaceUsage,
                                 ServerMessager serverMessager,
                                 Hasher hasher,
                                 List<String> usernames,
                                 boolean isJavascript) {
        super(coreNode, account, social, blockBuffer, batCave, batCache, unbufferedMutable, tree, synchronizer, instanceAdmin, spaceUsage,
                serverMessager, hasher, usernames, isJavascript);
        this.blockBuffer = blockBuffer;
        this.pointerBuffer = mutableBuffer;
        this.bulkCommitter = new ServerBulkCommitter(blockBuffer.target(),
                new LegacyBulkCommitter(blockBuffer.target(), unbufferedMutable, hasher), hasher);
        this.bufferSize = bufferSize;
        synchronizer.setCommitterBuilder(this::buildCommitter);
        synchronizer.setFlusher((o, v, w) -> commit(o, w).thenApply(b -> v));
    }

    @Override
    public Committer buildCommitter(Committer c, PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        return (o, w, wd, e, tid) -> (wd.isEmpty() ? Futures.of(MaybeMultihash.empty()) :
                blockBuffer.put(o, w.publicKeyHash, new byte[0], wd.get().serialize(), tid).thenApply(MaybeMultihash::new))
                .thenCompose(newHash -> {
                    PointerUpdate update = pointerBuffer.addWrite(o, w, newHash, e.hash, e.sequence);
                    return maybeCommit(o, commitWatcher)
                            .thenApply(x -> new Snapshot(w.publicKeyHash, new CommittedWriterData(newHash, wd, update.sequence)));
                });
    }

    public int bufferedSize() {
        return blockBuffer.totalSize();
    }

    public NetworkAccess disableCommits() {
        safeToCommit = false;
        return this;
    }

    public NetworkAccess enableCommits() {
        safeToCommit = true;
        return this;
    }

    @Override
    public NetworkAccess clear() {
        if (!blockBuffer.isEmpty())
            throw new IllegalStateException("Unwritten blocks!");
        NetworkAccess base = super.clear();
        BufferedStorage blockBuffer = this.blockBuffer.clone();
        WriteSynchronizer synchronizer = new WriteSynchronizer(base.mutable, blockBuffer, hasher);
        MutableTree tree = new MutableTreeImpl(base.mutable, blockBuffer, hasher, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, base.coreNode, base.account, base.social,
                base.mutable, base.batCave, base.batCache, tree, synchronizer, base.instanceAdmin, base.spaceUsage, base.serverMessager, hasher, usernames, isJavascript());
    }

    public void forceClear() {
        blockBuffer.clear();
        pointerBuffer.clear();
        synchronizer.clear();
    }

    @Override
    public NetworkAccess withStorage(Function<ContentAddressedStorage, ContentAddressedStorage> modifiedStorage) {
        BufferedStorage blockBuffer = this.blockBuffer.withStorage(modifiedStorage);
        WriteSynchronizer synchronizer = new WriteSynchronizer(super.mutable, blockBuffer, hasher);
        MutableTree tree = new MutableTreeImpl(mutable, blockBuffer, hasher, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, coreNode, account, social,
                mutable, batCave, batCache, tree, synchronizer, instanceAdmin, spaceUsage, serverMessager, hasher, usernames, isJavascript());
    }

    @Override
    public NetworkAccess withMutablePointerOfflineCache(Function<MutablePointers, MutablePointers> modifiedPointers) {
        MutablePointers newMutable = modifiedPointers.apply(mutable);
        BufferedPointers pointerBuffer = new BufferedPointers(newMutable);
        WriteSynchronizer synchronizer = new WriteSynchronizer(newMutable, blockBuffer, hasher);
        MutableTree tree = new MutableTreeImpl(newMutable, blockBuffer, hasher, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, coreNode, account, social,
                newMutable, batCave, batCache, tree, synchronizer, instanceAdmin, spaceUsage, serverMessager, hasher, usernames, isJavascript());
    }

    @Override
    public NetworkAccess withBatOfflineCache(Optional<EncryptedBatCache> batCache) {
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, coreNode, account, social,
                mutable, batCave, batCache, tree, synchronizer, instanceAdmin, spaceUsage, serverMessager, hasher, usernames, isJavascript());
    }

    @Override
    public NetworkAccess withAccountCache(Function<Account, Account> wrapper) {
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, coreNode, wrapper.apply(account), social,
                mutable, batCave, batCache, tree, synchronizer, instanceAdmin, spaceUsage, serverMessager, hasher, usernames, isJavascript());
    }

    public NetworkAccess withCorenode(CoreNode newCore) {
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, newCore, account, social,
                mutable, batCave, batCache, tree, synchronizer, instanceAdmin, spaceUsage, serverMessager, hasher, usernames, isJavascript());
    }

    @Override
    public CompletableFuture<Optional<Cid>> getLastCommittedRoot(PublicKeyHash writer, CommittedWriterData base) {
        Optional<Pair<Optional<Cid>, Optional<Long>>> lastCommitTarget = pointerBuffer.getCommittedPointerTarget(writer);
        if (lastCommitTarget.isEmpty()) {
            return Futures.of(base.hash.toOptional().map(c -> (Cid) c));
        }
        boolean higherBaseVersion = base.sequence.orElse(-1L) > lastCommitTarget.get().right.orElse(-1L);
        // If a later commit is not in local buffer or a merged pointer,
        // then there must have been an external commit, use it
        boolean isBufferedWrite = pointerBuffer.isBufferedWrite(writer, base.hash);
        if (higherBaseVersion && base.hash.isPresent() && !isBufferedWrite && ! blockBuffer.hasBufferedBlock((Cid) base.hash.get()))
            return Futures.of(base.hash.toOptional().map(c -> (Cid) c));
        return Futures.of(lastCommitTarget.get().left);
    }

    @Override
    public CompletableFuture<Optional<CryptreeNode>> getMetadata(CommittedWriterData base, AbsoluteCapability cap) {
        return getLastCommittedRoot(cap.writer, base)
                .thenCompose(committed -> super.getMetadata(base, cap, committed));
    }

    public boolean isFull() {
        return bufferedSize() >= bufferSize;
    }

    private CompletableFuture<Boolean> maybeCommit(PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        if (safeToCommit && isFull())
            return commit(owner, commitWatcher);
        return Futures.of(true);
    }

    /**
     * Resolve a known CAS conflict by merging our champ with the one the server actually has,
     * then committing that. Split out so a caller that has already been told the CAS failed can
     * come straight here: re-sending the update the server just rejected only wastes a round trip.
     */
    private CompletableFuture<Boolean> mergeAndCommit(
            PublicKeyHash owner,
            Pair<BufferedPointers.WriterUpdate, Optional<CommittedWriterData>> u,
            Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers,
            Optional<TransactionId> tid,
            PointerCasException cas) {
        MaybeMultihash actualExisting = cas.existing;
        if (actualExisting.equals(u.left.currentHash))
            return Futures.of(true);
        SigningPrivateKeyAndPublicHash signer = writers.get(u.left.writer);
        // The merge only writes cbor into the buffer, which doesn't need a real transaction
        TransactionId bufferTid = tid.orElseGet(() -> new TransactionId(Long.toString(System.currentTimeMillis())));
        return WriterData.getWriterData(owner, (Cid) u.left.prevHash.get(), Optional.empty(), blockBuffer)
                .thenCompose(original -> WriterData.getWriterData(owner, (Cid) u.left.currentHash.get(), Optional.empty(), blockBuffer)
                        .thenCompose(updated -> WriterData.getWriterData(owner, (Cid) actualExisting.get(), Optional.empty(), blockBuffer)
                                .thenCompose(remote -> ChampUtil.merge(owner, signer,
                                                MaybeMultihash.of(original.props.get().tree.get()),
                                                MaybeMultihash.of(updated.props.get().tree.get()),
                                                MaybeMultihash.of(remote.props.get().tree.get()),
                                                Optional.empty(), bufferTid, ChampWrapper.BIT_WIDTH,
                                                ChampWrapper.MAX_HASH_COLLISIONS_PER_LEVEL, y -> Futures.of(y.data),
                                                c -> (CborObject.CborMerkleLink) c, blockBuffer, hasher)
                                        .thenApply(p -> remote.props.get().withChamp(p.right)))))
                .thenCompose(newWD -> blockBuffer.put(owner, signer, newWD.serialize(), hasher, bufferTid)
                        .thenCompose(mergedRoot -> {
                            BufferedPointers.WriterUpdate merged = new BufferedPointers.WriterUpdate(u.left.writer,
                                    actualExisting, MaybeMultihash.of(mergedRoot), cas.sequence.map(s -> s + 1));
                            return commitWrites(owner, Collections.singletonList(new Pair<>(merged, Optional.empty())),
                                    writers, tid, false);
                        }));
    }

    @Override
    public synchronized CompletableFuture<Boolean> commit(PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        List<BufferedPointers.WriterUpdate> writerUpdates = pointerBuffer.getUpdates();
        if (blockBuffer.isEmpty() && writerUpdates.isEmpty())
            return Futures.of(true);
        // Condense pointers and do a mini GC to remove superfluous work
        List<Cid> roots = pointerBuffer.getRoots();
        if (roots.isEmpty())
            return Futures.of(true);
        blockBuffer.gc(roots);
        Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers = pointerBuffer.getSigners();
        List<Pair<BufferedPointers.WriterUpdate, Optional<CommittedWriterData>>> writes = blockBuffer.getAllWriterData(writerUpdates);

        // One operation can write to more than one owner's space, e.g. uploading a large file to a directory shared
        // with us also writes the upload transaction to our own space. Each write must go to its own owner's server.
        LinkedHashMap<PublicKeyHash, List<Pair<BufferedPointers.WriterUpdate, Optional<CommittedWriterData>>>> byOwner =
                new LinkedHashMap<>();
        for (Pair<BufferedPointers.WriterUpdate, Optional<CommittedWriterData>> write : writes)
            byOwner.computeIfAbsent(pointerBuffer.getOwner(write.left.writer).orElse(owner), o -> new ArrayList<>())
                    .add(write);

        CompletableFuture<Boolean> res = new CompletableFuture<>();
        Futures.reduceAll(byOwner.entrySet(), true,
                        (done, e) -> commitOwner(e.getKey(), e.getValue(), writers).thenApply(b -> done && b),
                        (x, y) -> x && y)
                .thenApply(x -> {
                    pointerBuffer.clear();
                    blockBuffer.clear();
                    return commitWatcher.get();
                }).thenApply(res::complete)
                .exceptionally(t -> {
                    pointerBuffer.clear();
                    blockBuffer.clear();
                    res.completeExceptionally(t);
                    return true;
                });
        return res;
    }

    private CompletableFuture<Boolean> commitOwner(PublicKeyHash owner,
                                                   List<Pair<BufferedPointers.WriterUpdate, Optional<CommittedWriterData>>> writes,
                                                   Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers) {
        // Every commit's blocks are written before the pointer that names them, so they all need a
        // transaction to hold them across that window. The server opens one for the blocks travelling
        // inside the call and closes it only once the pointer has landed, so the only thing we have to
        // open one for here is the blocks we write ahead of the call: the large raw ones going to S3.
        return (blockBuffer.needsTransaction() ?
                blockBuffer.target().startTransaction(owner).thenApply(Optional::of) :
                Futures.of(Optional.<TransactionId>empty()))
                .thenCompose(tid -> commitWrites(owner, writes, writers, tid, true)
                        .thenCompose(ok -> Futures.reduceAll(writes.stream(), true,
                                (a, u) -> u.right
                                        .map(cwd -> synchronizer.updateWriterState(owner, u.left.writer, new Snapshot(u.left.writer, cwd)))
                                        .orElse(Futures.of(true)),
                                (x, y) -> x && y))
                        .thenCompose(x -> tid.map(t -> blockBuffer.target().closeTransaction(owner, t))
                                .orElse(Futures.of(true))));
    }

    /** Build a single bulk commit covering these writers' buffered blocks and pointer updates, and apply it.
     *
     * @param mergeOnCas whether to resolve a CAS conflict by merging, which is only possible for a single writer
     */
    private CompletableFuture<Boolean> commitWrites(PublicKeyHash owner,
                                                    List<Pair<BufferedPointers.WriterUpdate, Optional<CommittedWriterData>>> writes,
                                                    Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers,
                                                    Optional<TransactionId> tid,
                                                    boolean mergeOnCas) {
        return buildCommit(owner, writes, writers, tid)
                .thenCompose(bulk -> Futures.asyncExceptionally(
                        () -> bulkCommitter.commit(owner, bulk, new CommitContext(writers, writes.stream()
                                .filter(u -> ! u.left.prevHash.isPresent())
                                .map(u -> u.left.writer)
                                .collect(Collectors.toSet()),
                                writes.stream().collect(Collectors.toMap(u -> u.left.writer, u -> u.left.currentHash)),
                                writes.stream().collect(Collectors.toMap(u -> u.left.writer, u -> u.left.currentSequence))))
                                .thenApply(hashes -> {
                                    // The pointer updates may not have gone through mutable at all, so tell
                                    // any cache of them what they now are rather than leaving it stale.
                                    mutable.recordApplied(owner, bulk.writers.stream()
                                            .flatMap(w -> w.pointer.stream())
                                            .collect(Collectors.toList()));
                                    pointerBuffer.recordCommitted(writes.stream()
                                            .map(u -> u.left)
                                            .collect(Collectors.toList()));
                                    return true;
                                }),
                        t -> {
                            Throwable cause = Exceptions.getRootCause(t);
                            if (! mergeOnCas || writes.size() > 1 || !(cause instanceof PointerCasException))
                                return Futures.errored(t);
                            // The server has just rejected exactly this update, so skip
                            // straight to merging rather than proposing it a second time.
                            return mergeAndCommit(owner, writes.get(0), writers, tid, (PointerCasException) cause);
                        }));
    }

    private CompletableFuture<BulkCommit> buildCommit(PublicKeyHash owner,
                                                      List<Pair<BufferedPointers.WriterUpdate, Optional<CommittedWriterData>>> writes,
                                                      Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers,
                                                      Optional<TransactionId> tid) {
        List<Set<Cid>> byRoot = blockBuffer.partitionByRoot(writes.stream()
                .map(u -> u.left.currentHash)
                .collect(Collectors.toList()));
        return Futures.combineAllInOrder(IntStream.range(0, writes.size())
                        .mapToObj(i -> {
                            BufferedPointers.WriterUpdate u = writes.get(i).left;
                            SigningPrivateKeyAndPublicHash signer = writers.get(u.writer);
                            PointerUpdate update = new PointerUpdate(u.prevHash, u.currentHash, u.currentSequence);
                            return signer.secret.signMessage(update.serialize())
                                    .thenApply(sig -> new SignedPointerUpdate(u.writer, sig))
                                    .thenCompose(pointer -> blockBuffer.buildWriterCommit(owner, u.writer,
                                            Optional.of(pointer), signer, tid, byRoot.get(i)));
                        })
                        .collect(Collectors.toList()))
                .thenApply(writerCommits -> new BulkCommit(tid, writerCommits));
    }

    @Override
    public String toString() {
        return "Blocks(" + blockBuffer.size() + "),Pointers(" + pointerBuffer.getUpdates().size()+")";
    }
}
