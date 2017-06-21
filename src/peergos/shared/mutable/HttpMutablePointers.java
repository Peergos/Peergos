
package peergos.shared.mutable;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.merklebtree.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class HttpMutablePointers implements MutablePointers
{
    private static final boolean LOGGING = true;
    private final HttpPoster poster;

    public static MutablePointers getInstance(URL coreURL) throws IOException {
        return new HttpMutablePointers(new JavaPoster(coreURL));
    }

    public HttpMutablePointers(HttpPoster poster)
    {
        System.out.println("Creating Http Mutable Pointers API at " + poster);
        this.poster = poster;
    }
   
    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash ownerPublicKey, PublicKeyHash sharingPublicKey, byte[] sharingKeySignedPayload)
    {
        long t1 = System.currentTimeMillis();
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(ownerPublicKey.serialize(), dout);
            Serialize.serialize(sharingPublicKey.serialize(), dout);
            Serialize.serialize(sharingKeySignedPayload, dout);
            dout.flush();

            return poster.postUnzip("mutable/setPointer", bout.toByteArray()).thenApply(res -> {
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
        } finally {
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                System.out.println("HttpMutablePointers.set took " + (t2 -t1) + "mS");
        }
    }

    @Override
    public CompletableFuture<MaybeMultihash> getPointer(PublicKeyHash encodedSharingKey)
    {
        long t1 = System.currentTimeMillis();
        try {
            return poster.postUnzip("mutable/getPointer", encodedSharingKey.serialize())
                    .thenApply(meta -> MaybeMultihash.fromCbor(CborObject.fromByteArray(meta)));
        } catch (Exception ioe) {
            ioe.printStackTrace();
            return CompletableFuture.completedFuture(MaybeMultihash.EMPTY());
        } finally {
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                System.out.println("HttpMutablePointers.get took " + (t2 -t1) + "mS");
        }
    }
}
