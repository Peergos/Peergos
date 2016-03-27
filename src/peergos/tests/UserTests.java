package peergos.tests;

import org.junit.*;
import peergos.corenode.*;
import peergos.user.*;

import java.io.*;
import java.net.*;

public class UserTests {

    UserContext context;

    @Before
    public void setup() throws IOException {
        String username = "test01";
        String password = "test01";
        UserWithRoot userWithRoot = UserUtil.generateUser(username, password);
        DHTClient.HTTP dht = new DHTClient.HTTP(new URL("http://localhost/"));
        Btree.HTTP btree = new Btree.HTTP(new URL("http://localhost/"));
        HTTPCoreNode coreNode = new HTTPCoreNode(new URL("http://localhost"+":"+HTTPCoreNodeServer.PORT+"/"));
        context = new UserContext(username, userWithRoot.getUser(), userWithRoot.getRoot(), dht, btree, coreNode);
        context.init();
    }

    @Test
    public void signup() {

    }
}
