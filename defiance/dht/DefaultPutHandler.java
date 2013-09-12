package defiance.dht;

import defiance.util.*;

import java.io.*;
import java.net.*;

public class DefaultPutHandler extends AbstractRequestHandler implements PutHandler
{
    byte[] key, value;

    public DefaultPutHandler(byte[] key, byte[] value)
    {
        this.key = key;
        this.value = value;
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
            setCompleted();
        } catch (IOException e)
        {
            e.printStackTrace();
        }

        setCompleted();
    }
}
