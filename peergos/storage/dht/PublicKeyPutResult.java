package peergos.storage.dht;

public class PublicKeyPutResult
{
    private final boolean success;

    PublicKeyPutResult(boolean success)
    {
        this.success = success;
    }

    public boolean getSuccess()
    {
        return success;
    }
}
