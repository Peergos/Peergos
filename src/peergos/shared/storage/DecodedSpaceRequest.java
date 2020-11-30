package peergos.shared.storage;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

@JsType
public class DecodedSpaceRequest {
    public final QuotaControl.LabelledSignedSpaceRequest source;
    public final QuotaControl.SpaceRequest decoded;

    public DecodedSpaceRequest(QuotaControl.LabelledSignedSpaceRequest source, QuotaControl.SpaceRequest decoded) {
        this.source = source;
        this.decoded = decoded;
    }

    @JsMethod
    public String getUsername() {
        return source.getUsername();
    }

    @JsMethod
    public int getSizeInMiB() {
        return (int) (decoded.getSizeInBytes() / (1024 * 1024));
    }

    @Override
    public String toString() {
        return decoded.toString();
    }

    public static CompletableFuture<List<DecodedSpaceRequest>> decodeSpaceRequests(
            List<QuotaControl.LabelledSignedSpaceRequest> in,
            CoreNode core,
            ContentAddressedStorage dht) {
        return Futures.combineAllInOrder(in.stream()
                .map(req -> core.getPublicKeyHash(req.username)
                        .thenCompose(keyHashOpt -> {
                            if (! keyHashOpt.isPresent())
                                throw new IllegalStateException("Couldn't retrieve public key for " + req.username);
                            PublicKeyHash identityHash = keyHashOpt.get();
                            return dht.getSigningKey(identityHash);
                        }).thenApply(keyOpt -> {
                            if (! keyOpt.isPresent())
                                throw new IllegalStateException("Couldn't retrieve public key for " + req.username);
                            try {
                                PublicSigningKey pubKey = keyOpt.get();
                                byte[] raw = pubKey.unsignMessage(req.signedRequest);
                                QuotaControl.SpaceRequest parsed = QuotaControl.SpaceRequest.fromCbor(CborObject.fromByteArray(raw));
                                return Optional.of(new DecodedSpaceRequest(req, parsed));
                            } catch (Exception e) {
                                e.printStackTrace();
                                return Optional.<DecodedSpaceRequest>empty();
                            }
                        }))
                .collect(Collectors.toList()))
                .thenApply(all -> all.stream()
                        .flatMap(Optional::stream)
                        .collect(Collectors.toList()));
    }
}
