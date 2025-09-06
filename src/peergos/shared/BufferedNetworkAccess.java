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

/** This will buffer block writes, and mutable pointer updates and commit in bulk
 *
 */
public class BufferedNetworkAccess extends NetworkAccess {

    public interface Flusher {
        CompletableFuture<Snapshot> commit(PublicKeyHash owner, Snapshot v, Supplier<Boolean> commitWatcher);
    }

    private final BufferedStorage blockBuffer;
    private final BufferedPointers pointerBuffer;
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
        this.bufferSize = bufferSize;
        synchronizer.setCommitterBuilder(this::buildCommitter);
        synchronizer.setFlusher((o, v, w) -> commit(o, w).thenApply(b -> v));
    }

    @Override
    public Committer buildCommitter(Committer c, PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        return (o, w, wd, e, tid) -> (wd.isEmpty() ? Futures.of(MaybeMultihash.empty()) :
                blockBuffer.put(o, w.publicKeyHash, new byte[0], wd.get().serialize(), tid).thenApply(MaybeMultihash::new))
                .thenCompose(newHash -> {
                    PointerUpdate update = pointerBuffer.addWrite(w, newHash, e.hash, e.sequence);
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
        // If a later commit is not in local buffer, then there must have been an external commit, use it
        if (higherBaseVersion && base.hash.isPresent() && ! blockBuffer.hasBufferedBlock((Cid) base.hash.get()))
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
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        blockBuffer.signBlocks(writers)
                .thenCompose(signed -> blockBuffer.target().startTransaction(owner)
                        .thenCompose(tid -> Futures.reduceAll(writes.stream(), true, (a,u) ->
                                        blockBuffer.commit(owner, u.left.writer, tid, signed)
                                                .thenCompose(b -> Futures.asyncExceptionally(
                                                                () -> pointerBuffer.commit(owner, writers.get(u.left.writer),
                                                                        new PointerUpdate(u.left.prevHash, u.left.currentHash, u.left.currentSequence)),
                                                                t -> {
                                                                    Throwable cause = Exceptions.getRootCause(t);
                                                                    if (cause instanceof PointerCasException) {
                                                                        PointerCasException cas = (PointerCasException) cause;
                                                                        MaybeMultihash actualExisting = cas.existing;
                                                                        return WriterData.getWriterData(owner, (Cid) u.left.prevHash.get(), Optional.empty(), blockBuffer)
                                                                                .thenCompose(original -> WriterData.getWriterData(owner, (Cid) u.left.currentHash.get(), Optional.empty(), blockBuffer)
                                                                                        .thenCompose(updated -> WriterData.getWriterData(owner, (Cid) actualExisting.get(), Optional.empty(), blockBuffer)
                                                                                                .thenCompose(remote -> ChampUtil.merge(owner, writers.get(u.left.writer),
                                                                                                                MaybeMultihash.of(original.props.get().tree.get()),
                                                                                                                MaybeMultihash.of(updated.props.get().tree.get()),
                                                                                                                MaybeMultihash.of(remote.props.get().tree.get()),
                                                                                                                Optional.empty(), tid, ChampWrapper.BIT_WIDTH,
                                                                                                                ChampWrapper.MAX_HASH_COLLISIONS_PER_LEVEL, x -> Futures.of(x.data),
                                                                                                                c -> (CborObject.CborMerkleLink)c, blockBuffer, hasher)
                                                                                                        .thenApply(p -> remote.props.get().withChamp(p.right)))))
                                                                                .thenCompose(newWD -> {
                                                                                    // 1. write the new writer data for the merged champs
                                                                                    // 2. flush the blocks
                                                                                    // 3. commit the new pointer
                                                                                    Optional<Long> seq = cas.sequence;
                                                                                    return blockBuffer.put(owner, writers.get(u.left.writer), newWD.serialize(), hasher, tid)
                                                                                            .thenCompose(mergedRoot -> blockBuffer.signBlocks(writers)
                                                                                                    .thenCompose(signedMore -> blockBuffer.commit(owner, u.left.writer, tid, signedMore))
                                                                                                    .thenCompose(x -> pointerBuffer.commit(owner, writers.get(u.left.writer),
                                                                                                            new PointerUpdate(actualExisting, MaybeMultihash.of(mergedRoot), seq.map(s -> s + 1)))));
                                                                                });
                                                                    }
                                                                    return Futures.errored(t);
                                                                }
                                                        )
                                                        .thenCompose(x -> u.right
                                                                .map(cwd -> synchronizer.updateWriterState(owner, u.left.writer, new Snapshot(u.left.writer, cwd)))
                                                                .orElse(Futures.of(true)))), (x,y) -> x && y)
                                .thenCompose(x -> blockBuffer.target().closeTransaction(owner, tid))
                                .thenApply(x -> {
                                    pointerBuffer.clear();
                                    blockBuffer.clear();
                                    return commitWatcher.get();
                                }))).thenApply(res::complete)
                .exceptionally(t -> {
                    pointerBuffer.clear();
                    blockBuffer.clear();
                    res.completeExceptionally(t);
                    return true;
                });
        return res;
    }

    @Override
    public String toString() {
        return "Blocks(" + blockBuffer.size() + "),Pointers(" + pointerBuffer.getUpdates().size()+")";
    }
}
