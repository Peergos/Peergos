package peergos.server.corenode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.merklebtree.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class PinningCoreNode implements CoreNode {
    private static final boolean LOGGING = true;
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
        HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(message));
        long t1 = System.currentTimeMillis();
        return storage.recursivePin(cas.updated.get()).thenCompose(pins -> {
            if (!pins.contains(cas.updated.get()))
                return CompletableFuture.completedFuture(false);
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                System.out.println("Pinning "+cas.updated+" took: " + (t2 -t1) + " mS");
            return target.setMetadataBlob(owner, signer, sharingKeySignedBtreeRootHashes)
                    .thenCompose(b -> {
                        if (!b)
                            return CompletableFuture.completedFuture(false);
                        long t3 = System.currentTimeMillis();
                        // unpin old root
                        return !cas.original.isPresent() ?
                                CompletableFuture.completedFuture(true) :
                                storage.recursiveUnpin(cas.original.get())
                                        .thenApply(unpins -> {
                                            long t4 = System.currentTimeMillis();
                                            if (LOGGING)
                                                System.out.println("Unpinning "+cas.original+" took: " + (t4 -t3) + " mS");
                                            return unpins.contains(cas.original.get());
                                        });
                    });
        });
    }

    @Override
    public CompletableFuture<Boolean> removeMetadataBlob(PublicSigningKey sharer, byte[] sharingKeySignedMapKeyPlusBlob) {
        // first pin new root
        byte[] message = sharer.unsignMessage(sharingKeySignedMapKeyPlusBlob);
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(message));
        try {
            Multihash oldRoot = Cid.cast(Serialize.deserializeByteArray(din, 256));
            Multihash newRoot = Cid.cast(Serialize.deserializeByteArray(din, 256));
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
