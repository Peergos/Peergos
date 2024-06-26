package peergos.shared.util;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.*;
import java.util.concurrent.*;

public class TimeLimitedClient {

    public static class SignedRequest implements Cborable {
        public final String path;
        public final long createdEpochMillis;

        public SignedRequest(String path, long createdEpochMillis) {
            this.path = path;
            this.createdEpochMillis = createdEpochMillis;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("p", new CborObject.CborString(path));
            state.put("t", new CborObject.CborLong(createdEpochMillis));
            return CborObject.CborMap.build(state);
        }

        public CompletableFuture<byte[]> sign(SecretSigningKey signer) {
            return signer.signMessage(serialize());
        }

        public static SignedRequest fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for SignedRequest! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            String path = m.getString("p");
            long created = m.getLong("t");
            return new SignedRequest(path, created);
        }
    }

    public static CompletableFuture<byte[]> signNow(SecretSigningKey signer) {
        byte[] time = new CborObject.CborLong(System.currentTimeMillis()).serialize();
        return signer.signMessage(time);
    }
}
