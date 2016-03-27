package peergos.tests;

import org.junit.*;
import peergos.corenode.*;
import peergos.crypto.*;
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
        UserPublicKey expected = UserPublicKey.fromString("7HvEWP6yd1UD8rOorfFrieJ8S7yC8+l3VisV9kXNiHmI7Eav7+3GTRSVBRCymItrzebUUoCi39M6rdgeOU9sXXFD");
        if (! expected.equals(userWithRoot.getUser().toUserPublicKey()))
            throw new IllegalStateException("Generated user diferent from the Javascript! \n"+userWithRoot.getUser().toUserPublicKey() + " != \n"+expected);
        DHTClient.HTTP dht = new DHTClient.HTTP(new URL("http://localhost:8000/"));
        Btree.HTTP btree = new Btree.HTTP(new URL("http://localhost:8000/"));
        HTTPCoreNode coreNode = new HTTPCoreNode(new URL("http://localhost:8000/"));
        context = new UserContext(username, userWithRoot.getUser(), userWithRoot.getRoot(), dht, btree, coreNode);
        context.init();
    }

    @Test
    public void login() {
        System.out.println();
    }
}
