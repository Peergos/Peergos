package peergos.shared.user;

import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.UserPublicKey;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.MaybeMultihash;
import peergos.shared.merklebtree.MerkleBTree;
import peergos.shared.merklebtree.PairMultihash;
import peergos.shared.storage.ContentAddressedStorage;

import java.util.concurrent.*;

public class BtreeImpl implements Btree {
    private final CoreNode coreNode;
    private final ContentAddressedStorage dht;

    public BtreeImpl(CoreNode coreNode, ContentAddressedStorage dht) {
        this.coreNode = coreNode;
        this.dht = dht;
    }

    @Override
    public CompletableFuture<PairMultihash> put(UserPublicKey sharingKey, byte[] mapKey, Multihash value) {
        return coreNode.getMetadataBlob(sharingKey).thenCompose(rootHash -> MerkleBTree.create(sharingKey, rootHash, dht)
                .thenCompose(btree -> btree.put(sharingKey, mapKey, value))
                .thenApply(multihash -> new PairMultihash(rootHash, MaybeMultihash.of(multihash)))
        );
    }

    @Override
    public CompletableFuture<MaybeMultihash> get(UserPublicKey sharingKey, byte[] mapKey) {
        return coreNode.getMetadataBlob(sharingKey)
                .thenCompose(rootHash -> MerkleBTree.create(sharingKey, rootHash, dht))
                .thenCompose(btree -> btree.get(mapKey));
    }

    @Override
    public CompletableFuture<PairMultihash> remove(UserPublicKey sharingKey, byte[] mapKey) {
        return coreNode.getMetadataBlob(sharingKey)
                .thenCompose(rootHash -> MerkleBTree.create(sharingKey, rootHash, dht)
                        .thenCompose(btree -> btree.delete(sharingKey, mapKey))
                        .thenApply(newRoot -> new PairMultihash(rootHash, MaybeMultihash.of(newRoot))));
    }
}
