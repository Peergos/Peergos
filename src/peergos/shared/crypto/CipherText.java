package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.symmetric.*;

import java.util.*;
import java.util.function.*;

public class CipherText implements Cborable {

    private final byte[] nonce, cipherText;

    public CipherText(byte[] nonce, byte[] cipherText) {
        this.nonce = nonce;
        this.cipherText = cipherText;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(new CborObject.CborByteArray(nonce), new CborObject.CborByteArray(cipherText)));
    }

    public static CipherText fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for cipher text: " + cbor);

        List<CborObject> parts = ((CborObject.CborList) cbor).value;
        return new CipherText(((CborObject.CborByteArray) parts.get(0)).value, ((CborObject.CborByteArray) parts.get(1)).value);
    }

    public <T> T decrypt(SymmetricKey key, Function<byte[], T> converter) {
        return converter.apply(key.decrypt(cipherText, nonce));
    }
}
