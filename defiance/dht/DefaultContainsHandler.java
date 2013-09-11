package defiance.dht;

public class DefaultContainsHandler extends AbstractRequestHandler implements GetHandler
{
    private final byte[] key;

    // use isCompleted() && !isFailed() to determine is key is found

    public DefaultContainsHandler(byte[] key)
    {
        this.key = key;
    }

    @Override
    public synchronized void handleResult(GetOffer offer)
    {
        setCompleted();
    }

    @Override
    public synchronized byte[] getResult()
    {
        return null;
    }
}
