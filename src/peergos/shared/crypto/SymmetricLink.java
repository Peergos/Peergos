package peergos.shared.crypto;

import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.crypto.symmetric.TweetNaClKey;
import peergos.shared.util.ArrayOps;

import java.util.Arrays;

public class SymmetricLink
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
