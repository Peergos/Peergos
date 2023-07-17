package peergos.shared.social;
import java.util.logging.*;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class HttpSocialNetwork implements SocialNetworkProxy {
    private static final String P2P_PROXY_PROTOCOL = "/http";
	private static final Logger LOG = Logger.getGlobal();

    private final HttpPoster direct, p2p;

    public HttpSocialNetwork(HttpPoster direct, HttpPoster p2p)
    {
        LOG.info("Creating HTTP SocialNetwork API at " + direct + " and " + p2p);
        this.direct = direct;
        this.p2p = p2p;
    }

    private static String getProxyUrlPrefix(Multihash targetId) {
        return "/p2p/" + targetId.toString() + P2P_PROXY_PROTOCOL + "/";
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
        return poster.postUnzip(urlPrefix + Constants.SOCIAL_URL + "followRequest?owner=" + encode(target.toString()), encryptedPermission).thenApply(res -> {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            try {
                return din.readBoolean();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner, byte[] signedTime) {
        return getFollowRequests("", direct, owner, signedTime);
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(Multihash targetServerId, PublicKeyHash owner, byte[] signedTime) {
        return getFollowRequests(getProxyUrlPrefix(targetServerId), p2p, owner, signedTime);
    }

    private CompletableFuture<byte[]> getFollowRequests(String urlPrefix, HttpPoster poster, PublicKeyHash owner, byte[] signedTime)
    {
        return poster.get(urlPrefix + Constants.SOCIAL_URL + "getFollowRequests?owner=" + encode(owner.toString())
                + "&auth=" + ArrayOps.bytesToHex(signedTime)).thenApply(res -> {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            try {
                return CoreNodeUtils.deserializeByteArray(din);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
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
        return poster.postUnzip(urlPrefix + Constants.SOCIAL_URL + "removeFollowRequest?owner=" + encode(owner.toString()), signedRequest).thenApply(res -> {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            try {
                return din.readBoolean();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static String encode(String component) {
        try {
            return URLEncoder.encode(component, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
