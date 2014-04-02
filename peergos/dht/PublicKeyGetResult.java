package peergos.dht;

public class PublicKeyGetResult
{
    private final boolean success;
    private final byte[] publicKey;

    PublicKeyGetResult(boolean success, byte[] pub)
    {
        this.success = success;
        this.publicKey = pub;
    }

    public boolean getSuccess()
    {
        return success;
    }

    public byte[] getPublicKey()
    {
        return publicKey;
    }
}
