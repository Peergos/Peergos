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
    private final PublicKeyHash owner;
    private final Supplier<Boolean> commitWatcher;
    private final ContentAddressedStorage blocks;

    private BufferedNetworkAccess(BufferedStorage blockBuffer,
                                  BufferedPointers mutableBuffer,
                                  int bufferSize,
                                  PublicKeyHash owner,
                                  Supplier<Boolean> commitWatcher,
                                  CoreNode coreNode,
                                  Account account,
                                  SocialNetwork social,
                                  ContentAddressedStorage dhtClient,
                                  BatCave batCave,
                                  MutablePointers mutable,
                                  MutableTree tree,
                                  WriteSynchronizer synchronizer,
                                  InstanceAdmin instanceAdmin,
                                  SpaceUsage spaceUsage,
                                  ServerMessager serverMessager,
                                  Hasher hasher,
                                  List<String> usernames,
                                  boolean isJavascript) {
        super(coreNode, account, social, blockBuffer, batCave, mutable, tree, synchronizer, instanceAdmin, spaceUsage,
                serverMessager, hasher, usernames, isJavascript);
        this.blockBuffer = blockBuffer;
        this.pointerBuffer = mutableBuffer;
        this.bufferSize = bufferSize;
        this.owner = owner;
        this.commitWatcher = commitWatcher;
        this.blocks = dhtClient;
        pointerBuffer.watchUpdates(() -> maybeCommit());
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

    public Committer buildCommitter(Committer c) {
        targetCommitter = c;
        return (o, w, wd, e, tid) -> blockBuffer.put(owner, w.publicKeyHash, new byte[0], wd.serialize(), tid)
                .thenApply(newHash -> {
                    CommittedWriterData updated = new CommittedWriterData(MaybeMultihash.of(newHash), wd);
                    PublicKeyHash writer = w.publicKeyHash;
                    writers.put(writer, w);
                    if (writerUpdates.isEmpty())
                        writerUpdates.add(new WriterUpdate(writer, e, updated));
                    else {
                        WriterUpdate last = writerUpdates.get(writerUpdates.size() - 1);
                        if (last.writer.equals(writer))
                            writerUpdates.set(writerUpdates.size() - 1, new WriterUpdate(writer, last.prev, updated));
                        else {
                            writerUpdates.add(new WriterUpdate(writer, e, updated));
                        }
                    }
                    return new Snapshot(writer, updated);
                });
    }

    public int bufferedSize() {
        return blockBuffer.totalSize();
    }

    private CompletableFuture<Boolean> maybeCommit() {
        if (bufferedSize() >= bufferSize)
            return commit();
        return Futures.of(true);
    }

    private List<Cid> getRoots() {
        return writerUpdates.stream()
                .flatMap(u -> u.current.hash.toOptional().stream())
                .map(c -> (Cid)c)
                .collect(Collectors.toList());
    }

    private CompletableFuture<List<Snapshot>> commitPointers(TransactionId tid) {
        return Futures.combineAllInOrder(writerUpdates.stream()
                .map(u -> targetCommitter.commit(owner, writers.get(u.writer), u.current.props, u.prev, tid))
                .collect(Collectors.toList()));
    }

    public synchronized CompletableFuture<Boolean> commit() {
        // Condense pointers and do a mini GC to remove superfluous work
        blockBuffer.gc(getRoots());
        return blockBuffer.signBlocks(writers)
                .thenCompose(b -> blocks.startTransaction(owner))
                .thenCompose(tid -> blockBuffer.commit(owner, tid)
                        .thenCompose(b -> commitPointers(tid))
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

    public static BufferedNetworkAccess build(NetworkAccess base,
                                              int bufferSize,
                                              PublicKeyHash owner,
                                              Supplier<Boolean> commitWatcher,
                                              Hasher h) {
        BufferedStorage blockBuffer = new BufferedStorage(base.dhtClient, h);
        BufferedPointers mutableBuffer = new BufferedPointers(base.mutable);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutableBuffer, blockBuffer, h);
        MutableTree tree = new MutableTreeImpl(mutableBuffer, blockBuffer, h, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, mutableBuffer, bufferSize, owner, commitWatcher, base.coreNode, base.account, base.social,
                base.dhtClient, base.batCave, mutableBuffer, tree, synchronizer, base.instanceAdmin,
                base.spaceUsage, base.serverMessager, base.hasher, base.usernames, base.isJavascript());
    }
}
