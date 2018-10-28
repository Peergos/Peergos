package peergos.shared.social;
import java.util.logging.*;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class HttpSocialNetwork implements SocialNetworkProxy {
    private static final String P2P_PROXY_PROTOCOL = "http";
	private static final Logger LOG = Logger.getGlobal();

    private final HttpPoster direct, p2p;

    public HttpSocialNetwork(HttpPoster direct, HttpPoster p2p)
    {
        LOG.info("Creating HTTP SocialNetwork API at " + direct + "and " + p2p);
        this.direct = direct;
        this.p2p = p2p;
    }

    private static String getProxyUrlPrefix(Multihash targetId) {
        return "/http/proxy/" + targetId.toBase58() + "/" + P2P_PROXY_PROTOCOL + "/";
    }

    @Override
    public CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash target, byte[] encryptedPermission) {
        return sendFollowRequest("", direct, target, encryptedPermission);
    }

    @Override
    public CompletableFuture<Boolean> sendFollowRequest(Multihash targetServerId, PublicKeyHash target, byte[] encryptedPermission) {
        return sendFollowRequest(getProxyUrlPrefix(targetServerId), p2p, target, encryptedPermission);
    }

    private CompletableFuture<Boolean> sendFollowRequest(String urlPrefix, HttpPoster poster, PublicKeyHash target, byte[] encryptedPermission)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(target.serialize(), dout);
            Serialize.serialize(encryptedPermission, dout);
            dout.flush();

            return poster.postUnzip(urlPrefix + "social/followRequest", bout.toByteArray()).thenApply(res -> {
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
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner) {
        return getFollowRequests("", direct, owner);
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(Multihash targetServerId, PublicKeyHash owner) {
        return getFollowRequests(getProxyUrlPrefix(targetServerId), p2p, owner);
    }

    private CompletableFuture<byte[]> getFollowRequests(String urlPrefix, HttpPoster poster, PublicKeyHash owner)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);


            Serialize.serialize(owner.serialize(), dout);
            dout.flush();

            return poster.postUnzip(urlPrefix + "social/getFollowRequests", bout.toByteArray()).thenApply(res -> {
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
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] signedRequest) {
        return removeFollowRequest("", direct, owner, signedRequest);
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(Multihash targetServerId, PublicKeyHash owner, byte[] data) {
        return removeFollowRequest(getProxyUrlPrefix(targetServerId), p2p, owner, data);
    }

    private CompletableFuture<Boolean> removeFollowRequest(String urlPrefix, HttpPoster poster, PublicKeyHash owner, byte[] signedRequest)
    {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(owner.serialize(), dout);
            Serialize.serialize(signedRequest, dout);
            dout.flush();

            return poster.postUnzip(urlPrefix + "social/removeFollowRequest", bout.toByteArray()).thenApply(res -> {
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
