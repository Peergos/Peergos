package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.net.ConfigHandler;

/** The desktop app may talk plain http to a server on this machine or the local network, but
 *  nothing further away.
 */
public class ServerUrlTests {

    @Test
    public void plainHttpOnlyOnThisMachineOrTheLan() {
        for (String ok : new String[]{
                "https://peergos.net",
                "http://localhost:8000",
                "http://127.0.0.1:8000",
                "http://192.168.1.10:8000",
                "http://10.0.0.5:8000",
                "http://172.16.4.2:8000"})
            Assert.assertEquals(ok, ConfigHandler.validateServerUrl(ok));

        for (String refused : new String[]{
                "http://8.8.8.8:8000",
                "http://172.32.0.1:8000",
                "http://peergos.net"}) {
            try {
                ConfigHandler.validateServerUrl(refused);
                Assert.fail("Plain http to " + refused + " should be refused");
            } catch (IllegalStateException expected) {}
        }
    }
}
