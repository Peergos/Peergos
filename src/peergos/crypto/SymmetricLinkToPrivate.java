package peergos.crypto;

import peergos.crypto.symmetric.SymmetricKey;
import peergos.crypto.symmetric.TweetNaClKey;
import peergos.util.ArrayOps;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;

public class SymmetricLinkToPrivate
{
    final byte[] link;

    public SymmetricLinkToPrivate(SymmetricKey from, User to, byte[] iv)
    {
        link = ArrayOps.concat(iv, from.encrypt(to.serialize(), iv));
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

    public UserPublicKey target(SymmetricKey from) throws IOException
    {
        byte[] nonce = Arrays.copyOfRange(link, 0, TweetNaClKey.NONCE_BYTES);
        byte[] encoded = from.decrypt(Arrays.copyOfRange(link, TweetNaClKey.NONCE_BYTES, link.length), nonce);
        return User.deserialize(new DataInputStream(new ByteArrayInputStream(encoded)));
    }
}
