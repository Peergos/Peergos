package peergos.crypto;

public class AsymmetricLink
{
    private final byte[] link;

    public AsymmetricLink(User from, UserPublicKey to, SymmetricKey target)
    {
        link = to.encryptMessageFor(target.getKey(), from.secretBoxingKey);
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
        return new SymmetricKey(to.decryptMessage(link, from.getPublicBoxingKey()));
    }
}
