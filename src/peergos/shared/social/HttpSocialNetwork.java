package peergos.shared.social;
import java.util.logging.*;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class HttpSocialNetwork implements SocialNetwork {
	private static final Logger LOG = Logger.getGlobal();

    private final HttpPoster poster;

    public static SocialNetwork getInstance(URL coreURL) throws IOException {
        return new HttpSocialNetwork(new JavaPoster(coreURL));
    }

    public HttpSocialNetwork(HttpPoster poster)
    {
        LOG.info("Creating HTTP SocialNetwork API at " + poster);
        this.poster = poster;
    }

    @Override
    public CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash target, byte[] encryptedPermission)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(target.serialize(), dout);
            Serialize.serialize(encryptedPermission, dout);
            dout.flush();

            return poster.postUnzip("social/followRequest", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return din.readBoolean();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);


            Serialize.serialize(owner.serialize(), dout);
            dout.flush();

            return poster.postUnzip("social/getFollowRequests", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return CoreNodeUtils.deserializeByteArray(din);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return null;
        }
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] signedRequest)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(owner.serialize(), dout);
            Serialize.serialize(signedRequest, dout);
            dout.flush();

            return poster.postUnzip("social/removeFollowRequest", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return din.readBoolean();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return CompletableFuture.completedFuture(false);
        }
    }
}
