package peergos.dht;

import peergos.util.*;

import java.io.*;

public class DefaultPutHandler extends AbstractRequestHandler implements PutHandler
{
    private final byte[] key, value;
    private final PutHandlerCallback onComplete;
    private final ErrorHandlerCallback onError;
    private final Messenger messenger;

    public DefaultPutHandler(byte[] key, byte[] value, PutHandlerCallback onComplete, ErrorHandlerCallback onError, Messenger messenger)
    {
        this.key = key;
        this.value = value;
        this.onComplete = onComplete;
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
    public void handleOffer(PutOffer offer)
    {
        // upload file to target with messenger
        try
        {
            messenger.putFragment(offer.getTarget().addr, offer.getTarget().port, "/" + Arrays.bytesToHex(key), value);
            onComplete();
        } catch (IOException e)
        {
            onError(e);
        }
    }
}
