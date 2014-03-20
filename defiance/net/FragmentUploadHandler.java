package defiance.net;

import defiance.storage.Storage;
import defiance.util.ByteArrayWrapper;
import org.nereus.http.*;
import org.nereus.http.server.*;

import java.io.*;
import java.net.*;

public class FragmentUploadHandler extends DirectoryHandler
{
    private final Storage storage;

    public FragmentUploadHandler(File root, boolean useCache, Storage storage) throws IOException
    {
        super(root, useCache);
        this.storage = storage;
    }

    @Override
    protected void handlePostRequest(String resourcePath, InetAddress clientAddress, HTTPRequest request, HTTPResponse response) throws IOException
    {
        String name = URLDecoder.decode(resourcePath, "UTF-8");
        if (!storage.isWaitingFor(defiance.util.Arrays.hexToBytes(name)))
        {
            // delay to stop Denial Of Service
            try
            {
                Thread.sleep(10000);
            } catch (InterruptedException e)
            {
            }
            return;
        }

        HTTPResponseHeaders headers = response.getHeaders();
        try
        {
            InputStream uploadStream = request.getContentStream();
            if (uploadStream == null)
            {
                headers.configureAsOK();
                return;
            }

            byte[] buffer = new byte[2 * 1024 * 1024];
            ByteArrayOutputStream bout = new ByteArrayOutputStream();

            while (true)
            {
                int r = uploadStream.read(buffer);
                if (r < 0)
                    break;
                bout.write(buffer, 0, r);
            }
            uploadStream.close();
            storage.put(new ByteArrayWrapper(defiance.util.Arrays.hexToBytes(name)), bout.toByteArray());
        } catch (Exception e)
        {
            headers.configureAsTooLarge();
        } finally
        {
            response.sendResponseHeaders();
        }
    }
}
