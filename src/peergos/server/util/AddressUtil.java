package peergos.server.util;

import peergos.shared.io.ipfs.MultiAddress;

import java.net.*;

public class AddressUtil {
    public static URL getLocalAddress(int port) {
        try {
            return new URI("http://localhost:" + port).toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int getListenPort(String uri) {
        try {
            URL url = new URI(uri).toURL();
            return url.getPort();
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
