package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.crypto.symmetric.TweetNaClKey;
import peergos.shared.util.ArrayOps;

import java.util.Arrays;

public class SymmetricLink implements Cborable
{
    private final byte[] nonce, link;

    public SymmetricLink(byte[] link)
    {
        this.nonce = Arrays.copyOfRange(link, 0, TweetNaClKey.NONCE_BYTES);
        this.link = Arrays.copyOfRange(link, TweetNaClKey.NONCE_BYTES, link.length);
    }

    public byte[] serialize()
    {
        return ArrayOps.concat(nonce, link);
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborByteArray(serialize());
    }

    public SymmetricKey target(SymmetricKey from)
    {
        byte[] encoded = from.decrypt(link, nonce);
        return SymmetricKey.fromByteArray(encoded);
    }

    public static SymmetricLink fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborByteArray))
            throw new IllegalStateException("Incorrect cbor type for SymmetricLink: " + cbor);

        return new SymmetricLink(((CborObject.CborByteArray) cbor).value);
    }

    public static SymmetricLink fromPair(SymmetricKey from, SymmetricKey to) {
        byte[] nonce = from.createNonce();
        return new SymmetricLink(ArrayOps.concat(nonce, from.encrypt(to.serialize(), nonce)));
    }
}
