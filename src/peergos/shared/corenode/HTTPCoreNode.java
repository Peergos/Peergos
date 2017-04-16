
package peergos.shared.corenode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.merklebtree.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class HTTPCoreNode implements CoreNode
{
    private static final boolean LOGGING = true;
    private final HttpPoster poster;

    public static CoreNode getInstance(URL coreURL) throws IOException {
        return new HTTPCoreNode(new JavaPoster(coreURL));
    }

    public HTTPCoreNode(HttpPoster poster)
    {
        System.out.println("Creating HTTP Corenode API at " + poster);
        this.poster = poster;
    }

    @Override public CompletableFuture<Optional<PublicSigningKey>> getPublicKey(String username)
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
                    return Optional.of(PublicSigningKey.fromByteArray(publicKey));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override public CompletableFuture<String> getUsername(PublicSigningKey publicKey)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(publicKey.serialize(), dout);
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
                DataSource din = new DataSource(res);
                try {
                    int count = din.readInt();
                    List<UserPublicKeyLink> result = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        PublicSigningKey owner = PublicSigningKey.fromByteArray(din.readArray());
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
                Serialize.serialize(link.owner.serialize(), dout);
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

    @Override public CompletableFuture<Boolean> followRequest(PublicSigningKey target, byte[] encryptedPermission)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(target.serialize(), dout);
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

    @Override public CompletableFuture<List<String>> getUsernames(String prefix)
    {
        return poster.postUnzip("core/getUsernamesGzip/"+prefix, new byte[0])
                .thenApply(raw -> (List) JSONParser.parse(new String(raw)));
    }

    @Override public CompletableFuture<byte[]> getFollowRequests(PublicSigningKey owner)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);


            Serialize.serialize(owner.serialize(), dout);
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
    
    @Override public CompletableFuture<Boolean> removeFollowRequest(PublicSigningKey owner, byte[] signedRequest)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(owner.serialize(), dout);
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

    @Override public void close() {}
}
