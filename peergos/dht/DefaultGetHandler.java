package peergos.dht;

import peergos.util.*;

import java.io.*;

public class DefaultGetHandler extends AbstractRequestHandler implements GetHandler
{
    final byte[] key;
    byte[] value;
    private final GetHandlerCallback onComplete;
    private final ErrorHandlerCallback onError;
    private final Messenger messenger;

    public DefaultGetHandler(byte[] key, GetHandlerCallback oncomplete, ErrorHandlerCallback onError, Messenger messenger)
    {
        super();
        this.key = key;
        this.onComplete = oncomplete;
        this.onError = onError;
        this.messenger = messenger;
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
    public synchronized void handleResult(GetOffer offer)
    {
        // download fragment using messenger
        try
        {
            value = messenger.getFragment(offer.getTarget().addr, offer.getTarget().port, "/"+Arrays.bytesToHex(key));
            onComplete();
        } catch (IOException e)
        {
            onError(e);
        }
    }

    @Override
    public synchronized byte[] getResult()
    {
        return value;
    }
}
