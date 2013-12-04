package defiance.dht;

import defiance.util.*;

import java.io.*;
import java.net.*;

public class DefaultGetHandler extends AbstractRequestHandler implements GetHandler
{
    final byte[] key;
    byte[] value;

    public DefaultGetHandler(byte[] key)
    {
        this.key = key;
    }

    @Override
    public synchronized void handleResult(GetOffer offer)
    {
        // download fragment using HTTP GET
        try
        {
            URL target = new URL("http", offer.getTarget().addr.getHostName(), offer.getTarget().port, "/"+Arrays.bytesToHex(key));
            URLConnection conn = target.openConnection();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[2*1024*1024];
            ByteArrayOutputStream bout = new ByteArrayOutputStream();

            while (true)
            {
                int r = in.read(buf);
                if (r < 0)
                    break;
                bout.write(buf, 0, r);
            }
            value = bout.toByteArray();
            setCompleted();
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public synchronized byte[] getResult()
    {
        return value;
    }
}
