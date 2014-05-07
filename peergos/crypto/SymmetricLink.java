package peergos.crypto;

import peergos.util.ArrayOps;

import java.util.Arrays;

public class SymmetricLink
{
    final byte[] link;

    public SymmetricLink(SymmetricKey from, SymmetricKey to, byte[] iv)
    {
        link = ArrayOps.concat(iv, from.encrypt(to.getKey().getEncoded(), iv));
    }

    public SymmetricLink(SymmetricKey from, SymmetricKey to)
    {
        this(from, to, SymmetricKey.randomIV());
    }

    public SymmetricLink(byte[] link)
    {
        this.link = link;
    }

    public byte[] serialize()
    {
        return link;
    }

    public byte[] initializationVector()
    {
        return Arrays.copyOfRange(link, 0, SymmetricKey.IV_SIZE);
    }

    public SymmetricKey target(SymmetricKey from)
    {
        byte[] iv = Arrays.copyOfRange(link, 0, SymmetricKey.IV_SIZE);
        byte[] encoded = from.decrypt(Arrays.copyOfRange(link, SymmetricKey.IV_SIZE, link.length), iv);
        return new SymmetricKey(encoded);
    }
}
