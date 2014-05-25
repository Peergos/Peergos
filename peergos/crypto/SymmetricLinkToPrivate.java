package peergos.crypto;

import peergos.util.ArrayOps;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

public class SymmetricLinkToPrivate
{
    final byte[] link;

    public SymmetricLinkToPrivate(SymmetricKey from, PrivateKey to, byte[] iv)
    {
        link = ArrayOps.concat(iv, from.encrypt(to.getEncoded(), iv));
    }

    public SymmetricLinkToPrivate(SymmetricKey from, PrivateKey to)
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

    public User target(SymmetricKey from, PublicKey pub)
    {
        byte[] iv = Arrays.copyOfRange(link, 0, SymmetricKey.IV_SIZE);
        byte[] encoded = from.decrypt(Arrays.copyOfRange(link, SymmetricKey.IV_SIZE, link.length), iv);
        return User.create(encoded, pub.getEncoded());
    }
}
