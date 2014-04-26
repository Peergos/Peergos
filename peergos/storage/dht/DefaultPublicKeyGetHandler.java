package peergos.storage.dht;

public class DefaultPublicKeyGetHandler extends AbstractRequestHandler implements PublicKeyGetHandler
{
    private final byte[] username;
    private final PublicKeyGetHandlerCallback onComplete;
    private final ErrorHandlerCallback onError;
    private boolean success;
    private byte[] publicKey;

    public DefaultPublicKeyGetHandler(byte[] username, PublicKeyGetHandlerCallback onComplete, ErrorHandlerCallback onError)
    {
        this.username = username;
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
    public synchronized void handleResult(PublicKeyGetResult result)
    {
        success = result.getSuccess();
        publicKey = result.getPublicKey();
        onComplete();
    }

    public synchronized boolean isValid()
    {
        return success;
    }

    public synchronized byte[] getResult()
    {
        return publicKey;
    }
}
