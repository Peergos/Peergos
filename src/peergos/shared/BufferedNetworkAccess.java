package peergos.shared;

import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** This will buffer block writes, and mutable pointer updates and commit in bulk
 *
 */
public class BufferedNetworkAccess extends NetworkAccess {

    private final BufferedStorage blockBuffer;
    private final BufferedPointers pointerBuffer;
    private final int bufferSize;
    private Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers = new HashMap<>();
    private final List<WriterUpdate> writerUpdates = new ArrayList<>();
    private Committer targetCommitter;
    private final ContentAddressedStorage blocks;
    private boolean safeToCommit = true;

    public BufferedNetworkAccess(BufferedStorage blockBuffer,
                                 BufferedPointers mutableBuffer,
                                 int bufferSize,
                                 CoreNode coreNode,
                                 Account account,
                                 SocialNetwork social,
                                 ContentAddressedStorage dhtClient,
                                 BatCave batCave,
                                 MutableTree tree,
                                 WriteSynchronizer synchronizer,
                                 InstanceAdmin instanceAdmin,
                                 SpaceUsage spaceUsage,
                                 ServerMessager serverMessager,
                                 Hasher hasher,
                                 List<String> usernames,
                                 boolean isJavascript) {
        super(coreNode, account, social, blockBuffer, batCave, mutableBuffer, tree, synchronizer, instanceAdmin, spaceUsage,
                serverMessager, hasher, usernames, isJavascript);
        this.blockBuffer = blockBuffer;
        this.pointerBuffer = mutableBuffer;
        this.bufferSize = bufferSize;
        this.blocks = dhtClient;
        synchronizer.setCommitterBuilder(this::buildCommitter);
        synchronizer.setFlusher((o, v) -> commit(o).thenApply(b -> v));
    }

    private static class WriterUpdate {
        public final PublicKeyHash writer;
        public final CommittedWriterData prev;
        public final CommittedWriterData current;

        public WriterUpdate(PublicKeyHash writer, CommittedWriterData prev, CommittedWriterData current) {
            this.writer = writer;
            this.prev = prev;
            this.current = current;
        }
    }

    public Committer buildCommitter(Committer c, PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        targetCommitter = c;
        return (o, w, wd, e, tid) -> blockBuffer.put(owner, w.publicKeyHash, new byte[0], wd.serialize(), tid)
                .thenCompose(newHash -> {
                    CommittedWriterData updated;
                    PublicKeyHash writer = w.publicKeyHash;
                    writers.put(writer, w);
                    if (writerUpdates.isEmpty()) {
                        updated = new CommittedWriterData(MaybeMultihash.of(newHash), wd, PointerUpdate.increment(e.sequence));
                        writerUpdates.add(new WriterUpdate(writer, e, updated));
                    } else {
                        WriterUpdate last = writerUpdates.get(writerUpdates.size() - 1);
                        if (last.writer.equals(writer)) {
                            updated = new CommittedWriterData(MaybeMultihash.of(newHash), wd, last.current.sequence);
                            writerUpdates.set(writerUpdates.size() - 1, new WriterUpdate(writer, last.prev, updated));
                        } else {
                            updated = new CommittedWriterData(MaybeMultihash.of(newHash), wd, PointerUpdate.increment(e.sequence));
                            writerUpdates.add(new WriterUpdate(writer, e, updated));
                        }
                    }
                    return maybeCommit(owner, commitWatcher).thenApply(x -> new Snapshot(writer, updated));
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

    public NetworkAccess clear() {
        if (!blockBuffer.isEmpty())
            throw new IllegalStateException("Unwritten blocks!");
        NetworkAccess base = super.clear();
        BufferedStorage blockBuffer = new BufferedStorage(base.dhtClient, hasher);
        BufferedPointers mutableBuffer = new BufferedPointers(base.mutable);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutableBuffer, blockBuffer, hasher);
        MutableTree tree = new MutableTreeImpl(mutableBuffer, blockBuffer, hasher, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, mutableBuffer, bufferSize, base.coreNode, base.account, base.social, base.dhtClient,
                base.batCave, tree, synchronizer, base.instanceAdmin, base.spaceUsage, base.serverMessager, hasher, usernames, isJavascript());
    }

    public NetworkAccess withCorenode(CoreNode newCore) {
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, newCore, account, social, dhtClient,
                batCave, tree, synchronizer, instanceAdmin, spaceUsage, serverMessager, hasher, usernames, isJavascript());
    }

    public NetworkAccess withMutablePointerCache(int ttl) {
        BufferedPointers mutable = pointerBuffer.withCache(ttl);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dhtClient, hasher);
        MutableTree mutableTree = new MutableTreeImpl(mutable, dhtClient, hasher, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, mutable, bufferSize, coreNode, account, social, dhtClient,
                batCave, mutableTree, synchronizer, instanceAdmin, spaceUsage, serverMessager, hasher, usernames, isJavascript());
    }

    public boolean isFull() {
        return bufferedSize() >= bufferSize;
    }

    private CompletableFuture<Boolean> maybeCommit(PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        if (safeToCommit && isFull())
            return commit(owner, commitWatcher);
        return Futures.of(true);
    }

    private List<Cid> getRoots() {
        return writerUpdates.stream()
                .flatMap(u -> u.current.hash.toOptional().stream())
                .map(c -> (Cid)c)
                .collect(Collectors.toList());
    }

    private CompletableFuture<List<Snapshot>> commitPointers(TransactionId tid, PublicKeyHash owner) {
        return Futures.combineAllInOrder(writerUpdates.stream()
                .map(u -> targetCommitter.commit(owner, writers.get(u.writer), u.current.props, u.prev, tid))
                .collect(Collectors.toList()));
    }

    @Override
    public synchronized CompletableFuture<Boolean> commit(PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        if (blockBuffer.isEmpty() && pointerBuffer.isEmpty())
            return Futures.of(true);
        // Condense pointers and do a mini GC to remove superfluous work
        List<Cid> roots = getRoots();
        if (roots.isEmpty())
            throw new IllegalStateException("Where are the pointers?");
        blockBuffer.gc(roots);
        return blockBuffer.signBlocks(writers)
                .thenCompose(b -> blocks.startTransaction(owner))
                .thenCompose(tid -> blockBuffer.commit(owner, tid)
                        .thenCompose(b -> commitPointers(tid, owner))
                        .thenCompose(b -> pointerBuffer.commit())
                        .thenCompose(x -> blocks.closeTransaction(owner, tid))
                        .thenApply(x -> {
                            blockBuffer.clear();
                            pointerBuffer.clear();
                            writers.clear();
                            writerUpdates.clear();
                            return commitWatcher.get();
                        }));
    }

    @Override
    public String toString() {
        return "Blocks(" + blockBuffer.size() + "),Pointers(" + writerUpdates.size()+")";
    }
}
