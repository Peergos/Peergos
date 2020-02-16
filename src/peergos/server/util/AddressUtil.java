package peergos.server.util;

import peergos.shared.io.ipfs.multiaddr.*;

import java.net.*;

public class AddressUtil {
    public static URL getLocalAddress(int port) {
        try {
            return new URI("http://localhost:" + port).toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public static URL getAddress(MultiAddress addr) {
        try {
            return new URI("http://" + addr.getHost() + ":" + addr.getTCPPort()).toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
