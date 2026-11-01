package peergos.shared.social;

import peergos.shared.Crypto;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.asymmetric.mlkem.HybridCurve25519MLKEMPublicKey;
import peergos.shared.crypto.random.*;
import peergos.shared.util.Futures;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class BlindFollowRequest implements Cborable {
    /** Every request sent to a friend link pads to one size, big enough for a request signed with SPHINCS+, so its
     *  size doesn't reveal the sender's scheme (remove-pki §8). */
    public static final int LINKED_PADDING = 24 * 1024;
    public static final int LEGACY_PADDING = 512;
    public static final int MAX_SIZE = 32 * 1024;
    // a request signed by its recipient to remove it, with room for a SPHINCS+ signature
    public static final int MAX_SIGNED_SIZE = MAX_SIZE + 20 * 1024;

    public final PublicBoxingKey dummySource;
    public final PaddedAsymmetricCipherText followRequest;
    // The friend link it was sent to; absent on a request to the legacy followRequestReceiver
    public final Optional<byte[]> linkId;

    public BlindFollowRequest(PublicBoxingKey dummySource, PaddedAsymmetricCipherText followRequest, Optional<byte[]> linkId) {
        this.dummySource = dummySource;
        this.followRequest = followRequest;
        this.linkId = linkId;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("k", dummySource.toCbor());
        result.put("f", followRequest.toCbor());
        linkId.ifPresent(l -> result.put("l", new CborObject.CborByteArray(l)));
        return CborObject.CborMap.build(result);
    }

    public static BlindFollowRequest fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for BlindFollowRequest: "+  cbor);
        PublicBoxingKey dummysource = PublicBoxingKey.fromCbor(((CborObject.CborMap) cbor).get("k"));
        PaddedAsymmetricCipherText followRequest = PaddedAsymmetricCipherText.fromCbor(((CborObject.CborMap) cbor).get("f"));
        Optional<byte[]> linkId = ((CborObject.CborMap) cbor).getOptionalByteArray("l");
        return new BlindFollowRequest(dummysource, followRequest, linkId);
    }

    public static CompletableFuture<BlindFollowRequest> build(PublicBoxingKey targetBoxer, FollowRequest request, Crypto crypto) {
        return build(targetBoxer, request, Optional.empty(), crypto);
    }

    public static CompletableFuture<BlindFollowRequest> build(PublicBoxingKey targetBoxer, FollowRequest request, Optional<byte[]> linkId, Crypto crypto) {
        // create a tmp keypair whose public key we can prepend to the request without leaking information
        return (targetBoxer instanceof HybridCurve25519MLKEMPublicKey ?
                BoxingKeyPair.randomHybrid(crypto) :
                Futures.of(BoxingKeyPair.randomCurve25519(crypto.random, crypto.boxer)))
                .thenCompose(tmp -> PaddedAsymmetricCipherText.build(tmp.secretBoxingKey, targetBoxer, request,
                                linkId.isPresent() ? LINKED_PADDING : LEGACY_PADDING)
                        .thenApply(cipher -> new BlindFollowRequest(tmp.publicBoxingKey, cipher, linkId)));
    }
}
