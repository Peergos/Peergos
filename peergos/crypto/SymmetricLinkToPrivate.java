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
        this(from, to, from.createNonce());
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
        byte[] nonce = Arrays.copyOfRange(link, 0, SymmetricKey.NONCE_BYTES);
        byte[] encoded = from.decrypt(Arrays.copyOfRange(link, SymmetricKey.NONCE_BYTES, link.length), nonce);
        return User.deserialize(encoded);
    }
}
