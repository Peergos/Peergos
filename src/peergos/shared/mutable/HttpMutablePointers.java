
package peergos.shared.mutable;
import java.util.logging.*;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class HttpMutablePointers implements MutablePointersProxy {
	private static final Logger LOG = Logger.getGlobal();
	private static final String P2P_PROXY_PROTOCOL = "/http";

    private static final boolean LOGGING = true;
    private final HttpPoster direct, p2p;
    private final String directUrlPrefix;

    public HttpMutablePointers(HttpPoster direct, HttpPoster p2p)
    {
        LOG.info("Creating Http Mutable Pointers API at " + direct + " and " + p2p);
        this.directUrlPrefix = "";
        this.direct = direct;
        this.p2p = p2p;
    }

    /** create an instance that always proxies calls to the supplied node
     *
     * @param p2p
     * @param targetNodeID
     */
    public HttpMutablePointers(HttpPoster p2p, Multihash targetNodeID)
    {
        LOG.info("Creating proxying Http Mutable Pointers API at " + p2p);
        this.directUrlPrefix = getProxyUrlPrefix(targetNodeID);
        this.direct = p2p;
        this.p2p = p2p;
    }

    private static String getProxyUrlPrefix(Multihash targetId) {
        return "/p2p/" + targetId.toString() + P2P_PROXY_PROTOCOL + "/";
    }
   
    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash ownerPublicKey,
                                                 PublicKeyHash sharingPublicKey,
                                                 byte[] sharingKeySignedPayload) {
        return setPointer(directUrlPrefix, direct, ownerPublicKey, sharingPublicKey, sharingKeySignedPayload);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(Multihash targetId,
                                                 PublicKeyHash ownerPublicKey,
                                                 PublicKeyHash sharingPublicKey,
                                                 byte[] sharingKeySignedPayload) {
        return setPointer(getProxyUrlPrefix(targetId), p2p, ownerPublicKey, sharingPublicKey, sharingKeySignedPayload);
    }

    private CompletableFuture<Boolean> setPointer(String urlPrefix,
                                                  HttpPoster poster,
                                                  PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  byte[] writerSignedPayload) {
        long t1 = System.currentTimeMillis();
        try
        {
            return poster.postUnzip(urlPrefix + Constants.MUTABLE_POINTERS_URL + "setPointer?owner=" + owner + "&writer=" + writer, writerSignedPayload).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    return din.readBoolean();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("HttpMutablePointers.set took " + (t2 -t1) + "mS");
        }
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        return getPointer(directUrlPrefix, direct, owner, writer);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(Multihash targetId, PublicKeyHash owner, PublicKeyHash writer) {
        return getPointer(getProxyUrlPrefix(targetId), p2p, owner, writer);
    }

    public CompletableFuture<Optional<byte[]>> getPointer(String urlPrefix, HttpPoster poster, PublicKeyHash owner, PublicKeyHash writer) {
        long t1 = System.currentTimeMillis();
        try {
            return poster.get(urlPrefix + Constants.MUTABLE_POINTERS_URL + "getPointer?owner=" + owner + "&writer=" + writer)
                    .thenApply(meta -> meta.length == 0 ? Optional.empty() : Optional.of(meta));
        } catch (Exception ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return CompletableFuture.completedFuture(Optional.empty());
        } finally {
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("HttpMutablePointers.get took " + (t2 -t1) + "mS for (" + owner + ", " + writer + ")");
        }
    }

    @Override
    public MutablePointers clearCache() {
        return this;
    }
}
