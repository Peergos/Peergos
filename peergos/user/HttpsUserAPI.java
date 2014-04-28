package peergos.user;

import peergos.storage.net.HTTPSMessenger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;

public class HttpsUserAPI
{
    private final URL target;

    public HttpsUserAPI(InetSocketAddress target) throws IOException
    {
        this.target = new URL("https", target.getHostString(), target.getPort(), HTTPSMessenger.USER_URL);
    }


}
