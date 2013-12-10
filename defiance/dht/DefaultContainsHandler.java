package defiance.dht;

public class DefaultContainsHandler extends AbstractRequestHandler implements ContainsHandler
{
    private final byte[] key;
    private boolean exists;
    private final ContainsHandlerCallback onComplete;

    // use isCompleted() && !isFailed() to determine if key is found

    public DefaultContainsHandler(byte[] key, ContainsHandlerCallback onComplete)
    {
        this.key = key;
        this.onComplete = onComplete;
    }

    protected void handleComplete()
    {
        onComplete.callback(this);
    }

    protected void handleError(Throwable e)
    {
        e.printStackTrace();
    }

    @Override
    public synchronized void handleResult(GetOffer offer)
    {
        exists = offer.getSize() > 0;
        onComplete();
    }

    @Override
    public synchronized boolean getResult()
    {
        return exists;
    }
}
