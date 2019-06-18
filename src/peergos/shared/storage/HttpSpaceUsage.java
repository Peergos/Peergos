package peergos.shared.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class HttpSpaceUsage implements SpaceUsageProxy {
    private static final String P2P_PROXY_PROTOCOL = "/http";
	private static final Logger LOG = Logger.getGlobal();

    private final HttpPoster direct, p2p;

    public HttpSpaceUsage(HttpPoster direct, HttpPoster p2p)
    {
        LOG.info("Creating HTTP SpaceUsage API at " + direct + " and " + p2p);
        this.direct = direct;
        this.p2p = p2p;
    }

    private static String getProxyUrlPrefix(Multihash targetId) {
        return "/p2p/" + targetId.toBase58() + P2P_PROXY_PROTOCOL + "/";
    }

    @Override
    public CompletableFuture<Long> getUsage(PublicKeyHash owner) {
        return getUsage("", direct, owner);
    }

    @Override
    public CompletableFuture<Long> getUsage(Multihash targetServerId, PublicKeyHash owner) {
        return getUsage(getProxyUrlPrefix(targetServerId), p2p, owner);
    }

    private CompletableFuture<Long> getUsage(String urlPrefix, HttpPoster poster, PublicKeyHash owner) {
        return poster.get(urlPrefix + Constants.SPACE_USAGE_URL + "usage?owner=" + encode(owner.toString())).thenApply(res -> {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            try {
                return din.readLong();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        return getQuota("", direct, owner, signedTime);
    }

    @Override
    public CompletableFuture<Long> getQuota(Multihash targetServerId, PublicKeyHash owner, byte[] signedTime) {
        return getQuota(getProxyUrlPrefix(targetServerId), p2p, owner, signedTime);
    }

    private CompletableFuture<Long> getQuota(String urlPrefix, HttpPoster poster, PublicKeyHash owner, byte[] signedTime)
    {
        return poster.get(urlPrefix + Constants.SPACE_USAGE_URL + "quota?owner=" + encode(owner.toString())
                + "&auth=" + ArrayOps.bytesToHex(signedTime)).thenApply(res -> {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            try {
                return din.readLong();
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
