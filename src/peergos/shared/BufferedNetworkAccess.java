package peergos.shared;

import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** This will buffer block writes, and mutable pointer updates and commit in bulk
 *
 */
public class BufferedNetworkAccess extends NetworkAccess {

    private final BufferedStorage blockBuffer;
    private final BufferedPointers pointerBuffer;
    private final int bufferSize;
    private Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers = new HashMap<>();
    private final PublicKeyHash owner;
    private final ContentAddressedStorage blocks;

    private BufferedNetworkAccess(BufferedStorage blockBuffer,
                                  BufferedPointers mutableBuffer,
                                  int bufferSize,
                                  PublicKeyHash owner,
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
        this.blocks = dhtClient;
        pointerBuffer.watchUpdates(() -> maybeCommit());
    }

    public void addWriter(SigningPrivateKeyAndPublicHash writer) {
        writers.put(writer.publicKeyHash, writer);
    }

    public int bufferedSize() {
        return blockBuffer.totalSize();
    }

    private CompletableFuture<Boolean> maybeCommit() {
        if (bufferedSize() >= bufferSize)
            return commit();
        return Futures.of(true);
    }

    public CompletableFuture<Boolean> commit() {
        // Condense pointers and do a mini GC to remove superfluous work
        pointerBuffer.condense(writers);
        blockBuffer.gc(pointerBuffer.getRoots());
        return blocks.startTransaction(owner)
                .thenCompose(tid -> blockBuffer.commit(owner, tid)
                        .thenCompose(b -> pointerBuffer.commit())
                        .thenCompose(x -> blocks.closeTransaction(owner, tid))
                        .thenApply(x -> {
                            blockBuffer.clear();
                            pointerBuffer.clear();
                            writers.clear();
                            return true;
                        }));
    }

    public static BufferedNetworkAccess build(NetworkAccess base, int bufferSize, PublicKeyHash owner, Hasher h) {
        BufferedStorage blockBuffer = new BufferedStorage(base.dhtClient, h);
        BufferedPointers mutableBuffer = new BufferedPointers(base.mutable);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutableBuffer, blockBuffer, h);
        MutableTree tree = new MutableTreeImpl(mutableBuffer, blockBuffer, h, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, mutableBuffer, bufferSize, owner, base.coreNode, base.account, base.social,
                base.dhtClient, base.batCave, mutableBuffer, tree, synchronizer, base.instanceAdmin,
                base.spaceUsage, base.serverMessager, base.hasher, base.usernames, base.isJavascript());
    }
}
