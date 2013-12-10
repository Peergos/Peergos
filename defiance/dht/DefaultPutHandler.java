package defiance.dht;

import defiance.util.*;

import java.io.*;
import java.net.*;

public class DefaultPutHandler extends AbstractRequestHandler implements PutHandler
{
    private final byte[] key, value;
    private final PutHandlerCallback onComplete;

    public DefaultPutHandler(byte[] key, byte[] value, PutHandlerCallback onComplete)
    {
        this.key = key;
        this.value = value;
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
    public void handleOffer(PutOffer offer)
    {
        // upload file to target with a HTTP POST request
        try
        {
            URL target = new URL("http", offer.getTarget().addr.getHostAddress(), offer.getTarget().port, "/" + Arrays.bytesToHex(key));
            HttpURLConnection conn = (HttpURLConnection) target.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            OutputStream out = conn.getOutputStream();
            out.write(value);
            out.flush();
            out.close();
            conn.getResponseCode();
            onComplete();
        } catch (IOException e)
        {
            onError(e);
        }
    }
}
