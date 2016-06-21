package peergos.crypto;

import peergos.crypto.symmetric.SymmetricKey;
import peergos.crypto.symmetric.TweetNaClKey;
import peergos.util.ArrayOps;

import java.util.Arrays;

public class SymmetricLink
{
    private final byte[] link, nonce;

    public SymmetricLink(byte[] link)
    {
        this.link = Arrays.copyOfRange(link, TweetNaClKey.NONCE_BYTES, link.length);
        this.nonce = Arrays.copyOfRange(link, 0, TweetNaClKey.NONCE_BYTES);
    }

    public byte[] serialize()
    {
        return ArrayOps.concat(nonce, link);
    }

    public SymmetricKey target(SymmetricKey from)
    {
        byte[] encoded = from.decrypt(link, nonce);
        return SymmetricKey.deserialize(encoded);
    }

    public static SymmetricLink fromPair(SymmetricKey from, SymmetricKey to) {
        byte[] nonce = from.createNonce();
        return new SymmetricLink(ArrayOps.concat(nonce, from.encrypt(to.serialize(), nonce)));
    }
}
