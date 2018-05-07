package peergos.server.corenode;

import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class NonWriteThroughCoreNode implements CoreNode {

    private final CoreNode source;
    private final CoreNode temp;
    private final ContentAddressedStorage ipfs;

    public NonWriteThroughCoreNode(CoreNode source, ContentAddressedStorage ipfs) {
        this.source = source;
        this.ipfs = ipfs;
        try {
            this.temp = UserRepository.buildSqlLite(":memory:", ipfs, 1000);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(String username) {
        try {
            Optional<PublicKeyHash> modified = temp.getPublicKeyHash(username).get();
            if (modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.getPublicKeyHash(username);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        try {
            String modified = temp.getUsername(key).get();
            if (! modified.isEmpty())
                return CompletableFuture.completedFuture(modified);
            return source.getUsername(key);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        try {
            List<UserPublicKeyLink> modified = temp.getChain(username).get();
            if (! modified.isEmpty())
                return CompletableFuture.completedFuture(modified);
            return source.getChain(username);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        try {
            List<UserPublicKeyLink> modified = temp.getChain(username).get();
            if (! modified.isEmpty())
                return temp.updateChain(username, chain);
            List<UserPublicKeyLink> existing = source.getChain(username).get();
            temp.updateChain(username, existing).get();
            return temp.updateChain(username, chain);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        try {
            return CompletableFuture.completedFuture(
                    Stream.concat(
                            source.getUsernames(prefix).get().stream(),
                            temp.getUsernames(prefix).get().stream())
                            .distinct()
                            .collect(Collectors.toList()));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
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
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner) {
        try {
            byte[] reqs = source.getFollowRequests(owner).get();
            DataSource din = new DataSource(reqs);
            List<byte[]> notDeleted = new ArrayList<>();
            List<ByteArrayWrapper> removed = removedFollowRequests.get(owner);
            int n = din.readInt();
            for (int i = 0; i < n; i++) {
                byte[] req = din.readArray();
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
            PublicSigningKey signer = ipfs.getSigningKey(owner).get().get();
            byte[] unsigned = signer.unsignMessage(signedEncryptedPermission);

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

    @Override
    public void close() throws IOException {}
}
