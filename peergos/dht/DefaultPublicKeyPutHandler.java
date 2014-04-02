package peergos.dht;

public class DefaultPublicKeyPutHandler extends AbstractRequestHandler implements PublicKeyPutHandler
{
    private final byte[] username, publicKey;
    private final PublicKeyPutHandlerCallback onComplete;
    private final ErrorHandlerCallback onError;
    private boolean success;

    public DefaultPublicKeyPutHandler(byte[] username, byte[] publicKey, PublicKeyPutHandlerCallback onComplete, ErrorHandlerCallback onError)
    {
        this.username = username;
        this.publicKey = publicKey;
        this.onComplete = onComplete;
        this.onError = onError;
    }

    protected void handleComplete()
    {
        onComplete.callback(this);
    }

    protected void handleError(Throwable e)
    {
        onError.callback(e);
    }

    @Override
    public synchronized void handleOffer(PublicKeyPutResult result)
    {
        success = result.getSuccess();
        onComplete();
    }

    public synchronized boolean getResult()
    {
        return success;
    }
}
