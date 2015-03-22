package peergos.crypto;

import peergos.util.ArrayOps;

import java.util.Arrays;

public class SymmetricLinkToPrivate
{
    final byte[] link;

    public SymmetricLinkToPrivate(SymmetricKey from, User to, byte[] iv)
    {
        link = ArrayOps.concat(iv, from.encrypt(to.getPrivateKeys(), iv));
    }

    public SymmetricLinkToPrivate(SymmetricKey from, User to)
    {
        this(from, to, SymmetricKey.randomIV());
    }

    public SymmetricLinkToPrivate(byte[] link)
    {
        this.link = link;
    }

    public byte[] serialize()
    {
        return link;
    }

    public User target(SymmetricKey from)
    {
        byte[] iv = Arrays.copyOfRange(link, 0, SymmetricKey.IV_SIZE);
        byte[] encoded = from.decrypt(Arrays.copyOfRange(link, SymmetricKey.IV_SIZE, link.length), iv);
        return User.deserialize(encoded);
    }
}
