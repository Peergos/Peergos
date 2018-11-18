package peergos.server.util;

import java.net.*;

public class AddressUtil {
    public static URL getLocalAddress(int port) {
        try {
            return new URI("http://localhost:" + port).toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
