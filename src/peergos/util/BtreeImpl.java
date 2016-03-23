package peergos.util;

import org.ipfs.api.Multihash;
import peergos.corenode.CoreNode;
import peergos.crypto.UserPublicKey;
import peergos.server.merklebtree.MaybeMultihash;
import peergos.server.merklebtree.MerkleBTree;
import peergos.server.merklebtree.PairMultihash;
import peergos.server.storage.ContentAddressedStorage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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
        Multihash raw = coreNode.getMetadataBlob(sharingKey);
        boolean isPresent = raw == null;
        MaybeMultihash rootHash = ! isPresent ? MaybeMultihash.EMPTY() : MaybeMultihash.of(raw);
        MerkleBTree btree = MerkleBTree.create(rootHash, dht);
        return new PairMultihash(
                rootHash,
                MaybeMultihash.of(btree.put(mapKey, value)));
    }

    @Override
    public MaybeMultihash get(UserPublicKey sharingKey, byte[] mapKey) throws IOException {
        Multihash raw = coreNode.getMetadataBlob(sharingKey);
        boolean isPresent = raw == null;
        MaybeMultihash rootHash = ! isPresent ? MaybeMultihash.EMPTY() : MaybeMultihash.of(raw);
        MerkleBTree btree = MerkleBTree.create(rootHash, dht);
        return btree.get(mapKey);
    }

    @Override
    public PairMultihash remove(UserPublicKey sharingKey, byte[] mapKey) throws IOException {
        Multihash raw = coreNode.getMetadataBlob(sharingKey);
        boolean isPresent = raw == null;
        MaybeMultihash rootHash = ! isPresent ? MaybeMultihash.EMPTY() : MaybeMultihash.of(raw);

        MerkleBTree btree = MerkleBTree.create(rootHash, dht);
        Multihash newRoot = btree.delete(mapKey);

        return new PairMultihash(rootHash,
                MaybeMultihash.of(newRoot));
    }
}
