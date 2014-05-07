package peergos.crypto;

import java.util.Arrays;

public class AsymmetricLink
{
    private final byte[] link;

    public AsymmetricLink(UserPublicKey from, SymmetricKey target)
    {
        link = from.encryptMessageFor(target.getKey().getEncoded());
    }

    public AsymmetricLink(byte[] link)
    {
        this.link = link;
    }

    public byte[] serialize()
    {
        return link;
    }

    public SymmetricKey target(User from)
    {
        return new SymmetricKey(from.decryptMessage(link));
    }
}
