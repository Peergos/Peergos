package peergos.shared.crypto;

import peergos.shared.crypto.asymmetric.PublicBoxingKey;
import peergos.shared.crypto.asymmetric.SecretBoxingKey;
import peergos.shared.crypto.symmetric.SymmetricKey;

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
