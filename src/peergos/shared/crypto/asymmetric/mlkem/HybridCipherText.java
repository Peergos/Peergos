package peergos.shared.crypto.asymmetric.mlkem;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.time.ZoneOffset;
import java.util.SortedMap;
import java.util.TreeMap;

public class HybridCipherText implements Cborable {
    public final byte[] curve25519Ciphertext, mlkemCipherText, encryptedInput, nonce;

    public HybridCipherText(byte[] curve25519Ciphertext, byte[] mlkemCipherText, byte[] encryptedInput, byte[] nonce) {
        this.curve25519Ciphertext = curve25519Ciphertext;
        this.mlkemCipherText = mlkemCipherText;
        this.encryptedInput = encryptedInput;
        this.nonce = nonce;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborByteArray(curve25519Ciphertext));
        state.put("m", new CborObject.CborByteArray(mlkemCipherText));
        state.put("i", new CborObject.CborByteArray(encryptedInput));
        state.put("n", new CborObject.CborByteArray(nonce));
        return CborObject.CborMap.build(state);
    }

    public static HybridCipherText fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for HybridCipherText! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new HybridCipherText(m.getByteArray("c"), m.getByteArray("m"), m.getByteArray("i"), m.getByteArray("n"));
    }
}
