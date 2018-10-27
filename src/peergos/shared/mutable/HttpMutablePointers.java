
package peergos.shared.mutable;
import java.util.logging.*;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class HttpMutablePointers implements MutablePointersProxy {
	private static final Logger LOG = Logger.getGlobal();
	private static final String P2P_PROXY_PROTOCOL = "http";

    private static final boolean LOGGING = true;
    private final HttpPoster poster;

    public HttpMutablePointers(HttpPoster poster)
    {
        LOG.info("Creating Http Mutable Pointers API at " + poster);
        this.poster = poster;
    }

    private static String getProxyUrlPrefix(Multihash targetId) {
        return "/http/proxy/" + targetId.toBase58() + "/" + P2P_PROXY_PROTOCOL + "/";
    }
   
    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash ownerPublicKey,
                                                 PublicKeyHash sharingPublicKey,
                                                 byte[] sharingKeySignedPayload) {
        return setPointer("", ownerPublicKey, sharingPublicKey, sharingKeySignedPayload);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(Multihash targetId,
                                                        PublicKeyHash ownerPublicKey,
                                                        PublicKeyHash sharingPublicKey,
                                                        byte[] sharingKeySignedPayload) {
        return setPointer(getProxyUrlPrefix(targetId), ownerPublicKey, sharingPublicKey, sharingKeySignedPayload);
    }

    private CompletableFuture<Boolean> setPointer(String urlPrefix,
                                                  PublicKeyHash ownerPublicKey,
                                                  PublicKeyHash sharingPublicKey,
                                                  byte[] sharingKeySignedPayload) {
        long t1 = System.currentTimeMillis();
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(ownerPublicKey.serialize(), dout);
            Serialize.serialize(sharingPublicKey.serialize(), dout);
            Serialize.serialize(sharingKeySignedPayload, dout);
            dout.flush();

            return poster.postUnzip(urlPrefix + "mutable/setPointer", bout.toByteArray()).thenApply(res -> {
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
        } finally {
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("HttpMutablePointers.set took " + (t2 -t1) + "mS");
        }
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash writer) {
        return getPointer("", writer);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(Multihash targetId, PublicKeyHash writer) {
        return getPointer(getProxyUrlPrefix(targetId), writer);
    }

    public CompletableFuture<Optional<byte[]>> getPointer(String urlPrefix, PublicKeyHash writer) {
        long t1 = System.currentTimeMillis();
        try {
            return poster.postUnzip(urlPrefix + "mutable/getPointer", writer.serialize())
                    .thenApply(meta -> meta.length == 0 ? Optional.empty() : Optional.of(meta));
        } catch (Exception ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return CompletableFuture.completedFuture(Optional.empty());
        } finally {
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("HttpMutablePointers.get took " + (t2 -t1) + "mS");
        }
    }
}
