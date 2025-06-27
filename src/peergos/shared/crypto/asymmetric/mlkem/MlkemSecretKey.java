package peergos.shared.crypto.asymmetric.mlkem;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

public class MlkemSecretKey implements Cborable {

    private final byte[] secretKeyBytes;
    private final Mlkem implementation;

    public MlkemSecretKey(byte[] secretKeyBytes, Mlkem implementation) {
        this.secretKeyBytes = secretKeyBytes;
        this.implementation = implementation;
    }

    public CompletableFuture<byte[]> decapsulate(byte[] cipherText) {
        return implementation.decapsulate(cipherText, secretKeyBytes);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("s", new CborObject.CborByteArray(secretKeyBytes));
        return CborObject.CborMap.build(state);
    }

    public static MlkemSecretKey fromCbor(Cborable cbor, Mlkem implementation) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MlkemSecretKey! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new MlkemSecretKey(m.getByteArray("s"), implementation);
    }
}
