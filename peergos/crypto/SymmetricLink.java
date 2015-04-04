package peergos.crypto;

import peergos.util.ArrayOps;

import java.util.Arrays;

public class SymmetricLink
{
    final byte[] link;

    public SymmetricLink(SymmetricKey from, SymmetricKey to, byte[] iv)
    {
        link = ArrayOps.concat(iv, from.encrypt(to.getKey(), iv));
    }

    public SymmetricLink(SymmetricKey from, SymmetricKey to)
    {
        this(from, to, SymmetricKey.createNonce());
    }

    public SymmetricLink(byte[] link)
    {
        this.link = link;
    }

    public byte[] serialize()
    {
        return link;
    }

    public byte[] getNonce()
    {
        return Arrays.copyOfRange(link, 0, SymmetricKey.NONCE_BYTES);
    }

    public SymmetricKey target(SymmetricKey from)
    {
        byte[] iv = Arrays.copyOfRange(link, 0, SymmetricKey.NONCE_BYTES);
        byte[] encoded = from.decrypt(Arrays.copyOfRange(link, SymmetricKey.NONCE_BYTES, link.length), iv);
        return new SymmetricKey(encoded);
    }
}
