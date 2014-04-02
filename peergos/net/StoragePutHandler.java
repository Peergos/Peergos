package peergos.net;

import com.sun.net.httpserver.HttpExchange;
import peergos.storage.Storage;
import peergos.util.Arrays;
import peergos.util.ByteArrayWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class StoragePutHandler extends StorageGetHandler
{

    public StoragePutHandler(Storage storage, String url)
    {
        super(storage, url);
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        if (httpExchange.getRequestMethod().equals("GET"))
        {
            handleGet(httpExchange);
        } else if (httpExchange.getRequestMethod().equals("PUT"))
        {
            handlePut(httpExchange);
        }
    }

    protected void handlePut(HttpExchange exchange) throws IOException
    {
        String filename = exchange.getRequestURI().toString().substring(uri.length());
        byte[] key = Arrays.hexToBytes(filename);
        if (storage.isWaitingFor(key))
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();

            InputStream uploadStream = exchange.getRequestBody();
            byte[] buffer = new byte[2 * 1024 * 1024];
            exchange.sendResponseHeaders(200, 0);
            while (true)
            {
                int r = uploadStream.read(buffer);
                if (r < 0)
                    break;
                bout.write(buffer, 0, r);
            }
            uploadStream.close();

            storage.put(new ByteArrayWrapper(peergos.util.Arrays.hexToBytes(filename)), bout.toByteArray());

            exchange.close();
        }
        else
        {
            // delay to stop Denial Of Service
            try
            {
                Thread.sleep(10000);
            } catch (InterruptedException e)
            {
            }
        }
    }
}
