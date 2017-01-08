package peergos.server.corenode;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.*;
import peergos.shared.merklebtree.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class PinningCoreNode implements CoreNode {
    private final CoreNode target;
    private final ContentAddressedStorage storage;

    public PinningCoreNode(CoreNode target, ContentAddressedStorage storage) {
        this.target = target;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<String> getUsername(PublicSigningKey publicKey) {
        return target.getUsername(publicKey);
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return target.getChain(username);
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        return target.updateChain(username, chain);
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return target.getUsernames(prefix);
    }

    @Override
    public CompletableFuture<Boolean> followRequest(PublicSigningKey target, byte[] encryptedPermission) {
        return this.target.followRequest(target, encryptedPermission);
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicSigningKey owner) {
        return target.getFollowRequests(owner);
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicSigningKey owner, byte[] data) {
        return target.removeFollowRequest(owner, data);
    }

    @Override
    public CompletableFuture<Boolean> setMetadataBlob(PublicSigningKey owner, PublicSigningKey signer, byte[] sharingKeySignedBtreeRootHashes) {
        // first pin new root
        byte[] message = signer.unsignMessage(sharingKeySignedBtreeRootHashes);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(message));
        try {
            byte[] rawOldRoot = Serialize.deserializeByteArray(din, 256);
            Optional<Multihash> oldRoot = rawOldRoot.length > 0 ? Optional.of(new Multihash(rawOldRoot)) : Optional.empty();
            Multihash newRoot = new Multihash(Serialize.deserializeByteArray(din, 256));
            return storage.recursivePin(newRoot).thenCompose(pins -> {
                if (!pins.contains(newRoot))
                    return CompletableFuture.completedFuture(false);
                return target.setMetadataBlob(owner, signer, sharingKeySignedBtreeRootHashes)
                        .thenCompose(b -> {
                            if (!b)
                                return CompletableFuture.completedFuture(false);
                            // unpin old root
                            return !oldRoot.isPresent() ?
                                    CompletableFuture.completedFuture(true) :
                                    storage.recursiveUnpin(oldRoot.get()).thenApply(unpins -> unpins.contains(oldRoot.get()));
                        });
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Boolean> removeMetadataBlob(PublicSigningKey sharer, byte[] sharingKeySignedMapKeyPlusBlob) {
        // first pin new root
        byte[] message = sharer.unsignMessage(sharingKeySignedMapKeyPlusBlob);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(message));
        try {
            Multihash oldRoot = new Multihash(Serialize.deserializeByteArray(din, 256));
            Multihash newRoot = new Multihash(Serialize.deserializeByteArray(din, 256));
            return storage.recursivePin(newRoot).thenCompose(pins -> {
                if (!pins.contains(newRoot))
                    return CompletableFuture.completedFuture(false);
                return target.removeMetadataBlob(sharer, sharingKeySignedMapKeyPlusBlob)
                        .thenCompose(b -> {
                            if (!b)
                                return CompletableFuture.completedFuture(false);
                            // unpin old root
                            return storage.recursiveUnpin(oldRoot).thenApply(unpins -> unpins.contains(oldRoot));
                        });
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<MaybeMultihash> getMetadataBlob(PublicSigningKey encodedSharingKey) {
        return target.getMetadataBlob(encodedSharingKey);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
