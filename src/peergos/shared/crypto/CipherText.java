package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.util.ProgressConsumer;

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
        return new CborObject.CborList(Arrays.asList(
                new CborObject.CborByteArray(nonce),
                new CborObject.CborByteArray(cipherText)
        ));
    }

    public static CipherText fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for cipher text: " + cbor);

        List<? extends Cborable> parts = ((CborObject.CborList) cbor).value;
        byte[] nonce = ((CborObject.CborByteArray) parts.get(0)).value;
        byte[] cipherText = ((CborObject.CborByteArray) parts.get(1)).value;
        return new CipherText(nonce, cipherText);
    }

    public static <T extends Cborable> CipherText build(SymmetricKey from, T secret) {
        byte[] nonce = from.createNonce();
        byte[] cipherText = from.encrypt(secret.serialize(), nonce);
        return new CipherText(nonce, cipherText);
    }

    public <T> T decrypt(SymmetricKey from, Function<CborObject, T> fromCbor) {
        byte[] secret = from.decrypt(cipherText, nonce);
        return fromCbor.apply(CborObject.fromByteArray(secret));
    }

    public <T> T decrypt(SymmetricKey from, Function<CborObject, T> fromCbor, ProgressConsumer<Long> monitor) {
        byte[] secret = from.decrypt(cipherText, nonce);
        monitor.accept((long)secret.length); //note: this is not accurate at all
        return fromCbor.apply(CborObject.fromByteArray(secret));
    }
}
