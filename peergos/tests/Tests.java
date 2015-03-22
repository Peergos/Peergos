package peergos.tests;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import peergos.corenode.AbstractCoreNode;
import peergos.corenode.SQLiteCoreNode;
import peergos.crypto.User;
import peergos.user.DHTUserAPI;
import peergos.user.MemoryDHTUserAPI;
import peergos.user.UserContext;
import peergos.util.Args;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

public class Tests
{

    public static void main(String[] args) throws IOException
    {
        Args.parse(args);
//        testClass(Components.class);
//        testClass(ErasureCodes.class);
//        testClass(GaloisPolynomial.Test.class);
//        testClass(Crypto.class);
//        testClass(SSL.class);
        if (Args.hasArg("local")) {
            localTests();
            return;
        }

        UserContext.Test.setStorageAddress(Args.getArg("clusterAddress", "localhost"));
        UserContext.Test.setCoreNodeAddress(Args.getArg("coreAddress", "localhost"));
        String user = Args.hasArg("randomise") ? "Bob" + System.currentTimeMillis() : "Bob";
        String follower = Args.hasArg("randomise") ? "Alice" + System.currentTimeMillis() : "Alice";
        UserContext.Test.setUser(user);
        UserContext.Test.setFollower(follower);

        testClass(UserContext.Test.class);
//        testClass(User.Test.class);
//        testClass(CoreNode.class);
    }

    private static void testClass(Class c)
    {
        Result result = JUnitCore.runClasses(c);

        for (Failure failure : result.getFailures()) {
            System.out.println(failure.toString());
        }
    }

    public static void localTests() {
        System.out.println("Doing local tests..");
        try {
            DHTUserAPI dht = new MemoryDHTUserAPI();
            AbstractCoreNode core = new SQLiteCoreNode("testDB.sqlite");
            contextTests(dht, core);
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            new File("testDB.sqlite").delete();
        }
    }

    public static void contextTests(DHTUserAPI dht, AbstractCoreNode core) {
        String ourname = "Bob";
        User us = User.generateUserCredentials(ourname, "password");
        UserContext bob = new UserContext(ourname, us, dht, core);

        String alicesName = "Alice";
        User them = User.generateUserCredentials(alicesName, "password");
        UserContext alice = new UserContext(alicesName, them, dht, core);

        if (!bob.isRegistered())
            if (!bob.register())
                throw new IllegalStateException("Couldn't register user!");
        if (!alice.isRegistered())
            if (!alice.register())
                throw new IllegalStateException("Couldn't register user!");

        bob.sendFollowRequest(alicesName);

        List<byte[]> reqs = alice.getFollowRequests();
        assert(reqs.size() == 1);
        UserContext.SharedRootDir root = alice.decodeFollowRequest(reqs.get(0));
        User sharer = root.owner;
    }
}
