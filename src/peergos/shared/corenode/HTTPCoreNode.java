
package peergos.shared.corenode;

import peergos.client.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.crypto.*;
import peergos.shared.merklebtree.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class HTTPCoreNode implements CoreNode
{
    private final HttpPoster poster;

    public static CoreNode getInstance(URL coreURL) throws IOException {
        return new HTTPCoreNode(new JavaPoster(coreURL));
    }

    public HTTPCoreNode(HttpPoster poster)
    {
        System.out.println("Creating HTTP Corenode API at " + poster);
        this.poster = poster;
    }

    @Override public CompletableFuture<Optional<UserPublicKey>> getPublicKey(String username)
    {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            dout.flush();

            CompletableFuture<byte[]> fut = poster.postUnzip("core/getPublicKey", bout.toByteArray());
            return fut.thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));

                try {
                    if (!din.readBoolean())
                        return Optional.empty();
                    byte[] publicKey = CoreNodeUtils.deserializeByteArray(din);
                    return Optional.of(UserPublicKey.deserialize(new DataInputStream(new ByteArrayInputStream(publicKey))));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override public CompletableFuture<String> getUsername(UserPublicKey publicKey)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(publicKey.toUserPublicKey().serialize(), dout);
            dout.flush();
            CompletableFuture<byte[]> fut = poster.post("core/getUsername", bout.toByteArray(), true);
            return fut.thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    String username = Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE);
                    return username;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            System.err.println("Couldn't connect to " + poster);
            ioe.printStackTrace();
            return null;
        }
    }

    @Override public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            dout.flush();

            return poster.postUnzip("core/getChain", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    int count = din.readInt();
                    List<UserPublicKeyLink> result = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        UserPublicKey owner = UserPublicKey.deserialize(din);
                        result.add(UserPublicKeyLink.fromByteArray(owner, Serialize.deserializeByteArray(din, UserPublicKeyLink.MAX_SIZE)));
                    }
                    return result;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            throw new IllegalStateException(ioe);
        }
    }

    @Override public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            dout.writeInt(chain.size());
            for (UserPublicKeyLink link : chain) {
                link.owner.serialize(dout);
                Serialize.serialize(link.toByteArray(), dout);
            }
            dout.flush();

            return poster.postUnzip("core/updateChain", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return din.readBoolean();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override public CompletableFuture<Boolean> followRequest(UserPublicKey target, byte[] encryptedPermission)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(target.toUserPublicKey().serialize(), dout);
            Serialize.serialize(encryptedPermission, dout);
            dout.flush();

            return poster.postUnzip("core/followRequest", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return din.readBoolean();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override public CompletableFuture<byte[]> getAllUsernamesGzip()
    {
        return poster.post("core/getAllUsernamesGzip", new byte[0], false).thenApply(res -> {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int r;

            try {
                while ((r = din.read(tmp)) >= 0) {
                    bout.write(tmp, 0, r);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return bout.toByteArray();
        });
    }

    @Override public CompletableFuture<byte[]> getFollowRequests(UserPublicKey owner)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);


            Serialize.serialize(owner.toUserPublicKey().serialize(), dout);
            dout.flush();

            return poster.postUnzip("core/getFollowRequests", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return CoreNodeUtils.deserializeByteArray(din);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
    }
    
    @Override public CompletableFuture<Boolean> removeFollowRequest(UserPublicKey owner, byte[] signedRequest)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(owner.toUserPublicKey().serialize(), dout);
            Serialize.serialize(signedRequest, dout);
            dout.flush();

            return poster.postUnzip("core/removeFollowRequest", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return din.readBoolean();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return CompletableFuture.completedFuture(false);
        }
    }
   
   @Override public CompletableFuture<Boolean> setMetadataBlob(UserPublicKey ownerPublicKey, UserPublicKey sharingPublicKey, byte[] sharingKeySignedPayload)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(ownerPublicKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(sharingPublicKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(sharingKeySignedPayload, dout);
            dout.flush();

            return poster.postUnzip("core/addMetadataBlob", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return din.readBoolean();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override public CompletableFuture<Boolean> removeMetadataBlob(UserPublicKey encodedSharingPublicKey, byte[] sharingKeySignedPayload)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(encodedSharingPublicKey.toUserPublicKey().serialize(), dout);
            Serialize.serialize(sharingKeySignedPayload, dout);
            dout.flush();

            return poster.postUnzip("core/removeMetadataBlob", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return din.readBoolean();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override public CompletableFuture<MaybeMultihash> getMetadataBlob(UserPublicKey encodedSharingKey)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);


            Serialize.serialize(encodedSharingKey.toUserPublicKey().serialize(), dout);
            dout.flush();

            return poster.postUnzip("core/getMetadataBlob", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    byte[] meta = CoreNodeUtils.deserializeByteArray(din);
                    if (meta.length == 0)
                        return MaybeMultihash.EMPTY();
                    return MaybeMultihash.of(new Multihash(meta));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return CompletableFuture.completedFuture(MaybeMultihash.EMPTY());
        }
    }

   @Override public void close()
    {}
}
