package peergos.crypto;

import peergos.crypto.asymmetric.PublicBoxingKey;
import peergos.crypto.asymmetric.SecretBoxingKey;
import peergos.crypto.symmetric.SymmetricKey;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public class AsymmetricLink
{
    private final byte[] link;

    public AsymmetricLink(SecretBoxingKey from, PublicBoxingKey to, SymmetricKey target)
    {
        link = to.encryptMessageFor(target.getKey(), from);
    }

    public AsymmetricLink(byte[] link)
    {
        this.link = link;
    }

    public byte[] serialize()
    {
        return link;
    }

    public SymmetricKey target(User to, UserPublicKey from)
    {
        return SymmetricKey.deserialize(to.decryptMessage(link, from.publicBoxingKey));
    }
}
