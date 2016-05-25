package peergos.corenode;

import org.ipfs.api.*;
import peergos.crypto.*;
import peergos.merklebtree.*;
import peergos.server.storage.*;
import peergos.util.*;

import java.io.*;
import java.util.*;

public class PinningCoreNode implements CoreNode {
    private final CoreNode target;
    private final ContentAddressedStorage storage;

    public PinningCoreNode(CoreNode target, ContentAddressedStorage storage) {
        this.target = target;
        this.storage = storage;
    }

    @Override
    public String getUsername(UserPublicKey publicKey) throws IOException {
        return target.getUsername(publicKey);
    }

    @Override
    public List<UserPublicKeyLink> getChain(String username) {
        return target.getChain(username);
    }

    @Override
    public boolean updateChain(String username, List<UserPublicKeyLink> chain) {
        return target.updateChain(username, chain);
    }

    @Override
    public byte[] getAllUsernamesGzip() throws IOException {
        return target.getAllUsernamesGzip();
    }

    @Override
    public boolean followRequest(UserPublicKey target, byte[] encryptedPermission) {
        return this.target.followRequest(target, encryptedPermission);
    }

    @Override
    public byte[] getFollowRequests(UserPublicKey owner) {
        return target.getFollowRequests(owner);
    }

    @Override
    public boolean removeFollowRequest(UserPublicKey owner, byte[] data) {
        return target.removeFollowRequest(owner, data);
    }

    @Override
    public boolean setMetadataBlob(UserPublicKey ownerPublicKey, UserPublicKey signer, byte[] sharingKeySignedBtreeRootHashes) throws IOException {
        // first pin new root
        byte[] message = signer.unsignMessage(sharingKeySignedBtreeRootHashes);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(message));
        byte[] rawOldRoot = Serialize.deserializeByteArray(din, 256);
        Optional<Multihash> oldRoot = rawOldRoot.length > 0 ? Optional.of(new Multihash(rawOldRoot)) : Optional.empty();
        Multihash newRoot = new Multihash(Serialize.deserializeByteArray(din, 256));
        if (!storage.recursivePin(newRoot))
            return false;
        boolean b = target.setMetadataBlob(ownerPublicKey, signer, sharingKeySignedBtreeRootHashes);
        if (!b)
            return false;
        // unpin old root
        return !oldRoot.isPresent() || storage.recursiveUnpin(oldRoot.get());
    }

    @Override
    public boolean removeMetadataBlob(UserPublicKey sharer, byte[] sharingKeySignedMapKeyPlusBlob) throws IOException {
        // first pin new root
        byte[] message = sharer.unsignMessage(sharingKeySignedMapKeyPlusBlob);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(message));
        Multihash oldRoot = new Multihash(Serialize.deserializeByteArray(din, 256));
        Multihash newRoot = new Multihash(Serialize.deserializeByteArray(din, 256));
        if (!storage.recursivePin(newRoot))
            return false;
        boolean b = target.removeMetadataBlob(sharer, sharingKeySignedMapKeyPlusBlob);
        if (!b)
            return false;
        // unpin old root
        return storage.recursiveUnpin(oldRoot);
    }

    @Override
    public MaybeMultihash getMetadataBlob(UserPublicKey encodedSharingKey) {
        return target.getMetadataBlob(encodedSharingKey);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
