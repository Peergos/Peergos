package peergos.tests;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import peergos.corenode.AbstractCoreNode;
import peergos.corenode.SQLiteCoreNode;
import peergos.crypto.User;
import peergos.user.*;
import peergos.user.fs.erasure.ErasureHandler;
import peergos.util.*;

import java.io.IOException;
import java.net.*;
import java.util.*;

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
//        testClass(ErasureHandler.Test.class);

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
            AbstractCoreNode core = SQLiteCoreNode.build(":memory:");
            contextTests(dht, core);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        } finally {
            System.exit(0);
        }
    }

    public static void contextTests(DHTUserAPI dht, AbstractCoreNode core) throws IOException {
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

        boolean followed = bob.sendFollowRequest(them);

        List<byte[]> reqs = alice.getFollowRequests();
        assert(reqs.size() == 1);
        EntryPoint rootEntry = alice.decodeFollowRequest(reqs.get(0));
        User sharer = (User)rootEntry.pointer.writer;

        // store a chunk in alice's space using the permitted sharing key (this could be alice or bob at this point)
        int frags = 120;
        int port = new Random(0).nextInt(Short.MAX_VALUE-1024) + 1024;

        InetSocketAddress address = new InetSocketAddress("localhost", port);
        for (int i = 0; i < frags; i++) {
            byte[] frag = ArrayOps.random(32);
            byte[] message = ArrayOps.concat(sharer.getPublicKeys(), frag);
            byte[] signed = sharer.signMessage(message);
            if (!core.registerFragmentStorage(us, address, us, signed)) {
                System.out.println("Failed to register fragment storage!");
            }
        }
        long quota = core.getQuota(us);
        System.out.println("Generated quota: " + quota/1024 + " KiB");
        long t1 = System.nanoTime();
        UserContext.Test.mediumFileTest(us, sharer, bob, alice);
        long t2 = System.nanoTime();
        System.out.printf("File test took %d mS\n", (t2 - t1) / 1000000);
    }
}
