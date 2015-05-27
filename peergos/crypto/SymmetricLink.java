package peergos.crypto;

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
        this.link = Arrays.copyOfRange(link, SymmetricKey.NONCE_BYTES, link.length);
        this.nonce = Arrays.copyOfRange(link, 0, SymmetricKey.NONCE_BYTES);
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
        return new SymmetricKey(encoded);
    }
}
