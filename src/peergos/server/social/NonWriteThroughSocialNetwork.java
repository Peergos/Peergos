package peergos.server.social;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class NonWriteThroughSocialNetwork implements SocialNetwork {

    private final SocialNetwork source;
    private final ContentAddressedStorage ipfs;

    public NonWriteThroughSocialNetwork(SocialNetwork source, ContentAddressedStorage ipfs) {
        this.source = source;
        this.ipfs = ipfs;
    }

    private final Map<PublicKeyHash, List<ByteArrayWrapper>> removedFollowRequests = new ConcurrentHashMap<>();
    private final Map<PublicKeyHash, List<ByteArrayWrapper>> newFollowRequests = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash target, byte[] encryptedPermission) {
        newFollowRequests.putIfAbsent(target, new ArrayList<>());
        ByteArrayWrapper wrappped = new ByteArrayWrapper(encryptedPermission);
        newFollowRequests.get(target).add(wrappped);
        removedFollowRequests.putIfAbsent(target, new ArrayList<>());
        removedFollowRequests.get(target).remove(wrappped);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner, byte[] signedTime) {
        try {
            byte[] reqs = source.getFollowRequests(owner, signedTime).join();
            CborObject cbor = CborObject.fromByteArray(reqs);
            List<byte[]> notDeleted = new ArrayList<>();
            List<ByteArrayWrapper> removed = removedFollowRequests.get(owner);
            CborObject.CborList list = (CborObject.CborList) cbor;
            for (Cborable reqCbor: list.value) {
                byte[] req = reqCbor.serialize();
                ByteArrayWrapper wrapped = new ByteArrayWrapper(req);
                if (! removed.contains(wrapped))
                    notDeleted.add(req);
            }
            notDeleted.addAll(newFollowRequests.get(owner).stream()
                    .map(w -> w.data)
                    .collect(Collectors.toList()));
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutput dout = new DataOutputStream(bout);
            dout.writeInt(notDeleted.size());
            for (byte[] req : notDeleted) {
                Serialize.serialize(req, dout);
            }
            return CompletableFuture.completedFuture(bout.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] signedEncryptedPermission) {
        try {
            PublicSigningKey signer = ipfs.getSigningKey(owner, owner).join().get();
            byte[] unsigned = signer.unsignMessage(signedEncryptedPermission).join();

            newFollowRequests.putIfAbsent(owner, new ArrayList<>());
            ByteArrayWrapper wrappped = new ByteArrayWrapper(unsigned);
            newFollowRequests.get(owner).remove(wrappped);
            removedFollowRequests.putIfAbsent(owner, new ArrayList<>());
            removedFollowRequests.get(owner).add(wrappped);
            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
