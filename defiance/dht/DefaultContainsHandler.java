package defiance.dht;

public class DefaultContainsHandler extends AbstractRequestHandler implements ContainsHandler
{
    private final byte[] key;
    private boolean exists;

    // use isCompleted() && !isFailed() to determine if key is found

    public DefaultContainsHandler(byte[] key)
    {
        this.key = key;
    }

    @Override
    public synchronized void handleResult(GetOffer offer)
    {
        exists = offer.getSize() > 0;
        setCompleted();
    }

    @Override
    public synchronized boolean getResult()
    {
        return exists;
    }
}
