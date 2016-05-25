package peergos.user;

import org.ipfs.api.Multihash;
import peergos.corenode.CoreNode;
import peergos.crypto.UserPublicKey;
import peergos.merklebtree.MaybeMultihash;
import peergos.merklebtree.MerkleBTree;
import peergos.merklebtree.PairMultihash;
import peergos.server.storage.ContentAddressedStorage;

import java.io.IOException;

public class BtreeImpl implements Btree {
    private final CoreNode coreNode;
    private final ContentAddressedStorage dht;

    public BtreeImpl(CoreNode coreNode, ContentAddressedStorage dht) {
        this.coreNode = coreNode;
        this.dht = dht;
    }

    @Override
    public PairMultihash put(UserPublicKey sharingKey, byte[] mapKey, Multihash value) throws IOException {
        MaybeMultihash rootHash = coreNode.getMetadataBlob(sharingKey);
        MerkleBTree btree = MerkleBTree.create(rootHash, dht);
        return new PairMultihash(
                rootHash,
                MaybeMultihash.of(btree.put(mapKey, value)));
    }

    @Override
    public MaybeMultihash get(UserPublicKey sharingKey, byte[] mapKey) throws IOException {
        MaybeMultihash rootHash = coreNode.getMetadataBlob(sharingKey);
        MerkleBTree btree = MerkleBTree.create(rootHash, dht);
        return btree.get(mapKey);
    }

    @Override
    public PairMultihash remove(UserPublicKey sharingKey, byte[] mapKey) throws IOException {
        MaybeMultihash rootHash = coreNode.getMetadataBlob(sharingKey);

        MerkleBTree btree = MerkleBTree.create(rootHash, dht);
        Multihash newRoot = btree.delete(mapKey);

        return new PairMultihash(rootHash,
                MaybeMultihash.of(newRoot));
    }
}
