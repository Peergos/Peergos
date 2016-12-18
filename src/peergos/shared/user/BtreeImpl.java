package peergos.shared.user;

import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.UserPublicKey;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.MaybeMultihash;
import peergos.shared.merklebtree.MerkleBTree;
import peergos.shared.merklebtree.PairMultihash;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.util.concurrent.*;

public class BtreeImpl implements Btree {
    private final CoreNode coreNode;
    private final ContentAddressedStorage dht;
    private static final boolean LOGGING = false;

    public BtreeImpl(CoreNode coreNode, ContentAddressedStorage dht) {
        this.coreNode = coreNode;
        this.dht = dht;
    }

    private <T> T log(T result, String toPrint) {
        if (LOGGING)
            System.out.println(toPrint);
        return result;
    }

    @Override
    public CompletableFuture<PairMultihash> put(UserPublicKey sharingKey, byte[] mapKey, Multihash value) {
        UserPublicKey publicSharingKey = sharingKey.toUserPublicKey();
        return coreNode.getMetadataBlob(publicSharingKey).thenCompose(rootHash -> MerkleBTree.create(publicSharingKey, rootHash, dht)
                .thenCompose(btree -> btree.put(publicSharingKey, mapKey, value))
                .thenApply(multihash -> new PairMultihash(rootHash, MaybeMultihash.of(multihash)))
                .thenApply(pair -> log(pair, "BTREE.put (" + ArrayOps.bytesToHex(mapKey) + ", " + value + ") => " + pair)));
    }

    @Override
    public CompletableFuture<MaybeMultihash> get(UserPublicKey sharingKey, byte[] mapKey) {
        UserPublicKey publicSharingKey = sharingKey.toUserPublicKey();
        return coreNode.getMetadataBlob(publicSharingKey)
                .thenCompose(rootHash -> MerkleBTree.create(publicSharingKey, rootHash, dht)
                .thenCompose(btree -> btree.get(mapKey))
                        .thenApply(pair -> log(pair, "BTREE.get (" + ArrayOps.bytesToHex(mapKey) + ", root="+rootHash+" => " + pair)));
    }

    @Override
    public CompletableFuture<PairMultihash> remove(UserPublicKey sharingKey, byte[] mapKey) {
        UserPublicKey publicSharingKey = sharingKey.toUserPublicKey();
        return coreNode.getMetadataBlob(publicSharingKey)
                .thenCompose(rootHash -> MerkleBTree.create(publicSharingKey, rootHash, dht)
                        .thenCompose(btree -> btree.delete(publicSharingKey, mapKey))
                        .thenApply(newRoot -> new PairMultihash(rootHash, MaybeMultihash.of(newRoot)))
                        .thenApply(pair -> log(pair, "BTREE.rm (" + ArrayOps.bytesToHex(mapKey)+ "  => " + pair)));
    }
}
