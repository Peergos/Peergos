package peergos.shared.storage;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.concurrent.*;

public interface SpaceUsage {

    CompletableFuture<Long> getUsage(PublicKeyHash owner);

    CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime);

    CompletableFuture<Boolean> requestSpace(PublicKeyHash owner, byte[] signedRequest);

    default CompletableFuture<Boolean> requestSpace(String username, SigningPrivateKeyAndPublicHash identity, long space) {
        SpaceRequest req = new SpaceRequest(username, space, System.currentTimeMillis(), Optional.empty());
        byte[] signedRequest = identity.secret.signMessage(req.serialize());
        return requestSpace(identity.publicKeyHash, signedRequest);
    }

    class LabelledSignedSpaceRequest implements Cborable {
        public final String username;
        public final byte[] signedRequest;

        public LabelledSignedSpaceRequest(String username, byte[] signedRequest) {
            this.username = username;
            this.signedRequest = signedRequest;
        }

        public String getUsername() {
            return username;
        }

        @Override
        public CborObject toCbor() {
            Map<String, CborObject> props = new TreeMap<>();
            props.put("u", new CborObject.CborString(username));
            props.put("r", new CborObject.CborByteArray(signedRequest));
            return CborObject.CborMap.build(props);
        }

        public static LabelledSignedSpaceRequest fromCbor(Cborable cbor) {
            CborObject.CborMap map = (CborObject.CborMap) cbor;
            String username = map.getString("u");
            byte[] req = map.getByteArray("r");
            return new LabelledSignedSpaceRequest(username, req);
        }
    }

    class SpaceRequest implements Cborable {
        public final String username;
        public final long bytes;
        public final long utcMillis;
        Optional<byte[]> paymentProof;

        public SpaceRequest(String username, long bytes, long utcMillis, Optional<byte[]> paymentProof) {
            if (paymentProof.isPresent() && paymentProof.get().length > 4096)
                throw new IllegalStateException("Payment proof too big!");
            this.username = username;
            this.bytes = bytes;
            this.utcMillis = utcMillis;
            this.paymentProof = paymentProof;
        }

        public long getSizeInBytes() {
            return bytes;
        }

        @Override
        public CborObject toCbor() {
            Map<String, CborObject> props = new TreeMap<>();
            props.put("u", new CborObject.CborString(username));
            props.put("s", new CborObject.CborLong(bytes));
            props.put("t", new CborObject.CborLong(utcMillis));
            if (paymentProof.isPresent())
                props.put("p", new CborObject.CborByteArray(paymentProof.get()));
            return CborObject.CborMap.build(props);
        }

        public static SpaceRequest fromCbor(Cborable cbor) {
            CborObject.CborMap map = (CborObject.CborMap) cbor;
            String username = map.getString("u");
            long bytes = map.getLong("s");
            long time = map.getLong("t");
            Optional<byte[]> proof = map.getOptionalByteArray("p");
            return new SpaceRequest(username, bytes, time, proof);
        }
    }
}
