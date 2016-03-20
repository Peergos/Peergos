package peergos.crypto;

import peergos.crypto.symmetric.SymmetricKey;
import peergos.crypto.symmetric.TweetNaClKey;
import peergos.util.ArrayOps;

import java.util.Arrays;

public class SymmetricLink
{
    final byte[] link, nonce;

    public SymmetricLink(SymmetricKey from, SymmetricKey to, byte[] iv)
    {
        link = from.encrypt(to.getKey(), iv);
        nonce = iv;
    }

    public SymmetricLink(byte[] link)
    {
        this.link = Arrays.copyOfRange(link, TweetNaClKey.NONCE_BYTES, link.length);
        this.nonce = Arrays.copyOfRange(link, 0, TweetNaClKey.NONCE_BYTES);
    }

    public byte[] serialize()
    {
        return ArrayOps.concat(nonce, link);
    }

    public byte[] getNonce()
    {
        return nonce;
    }

    public SymmetricKey target(SymmetricKey from)
    {
        byte[] encoded = from.decrypt(link, nonce);
        return SymmetricKey.deserialize(encoded);
    }

    public static SymmetricLink fromPair(SymmetricKey from, SymmetricKey to, byte[] nonce) {
    return new SymmetricLink(ArrayOps.concat(nonce, from.encrypt(to.serialize(), nonce)));
}
}
