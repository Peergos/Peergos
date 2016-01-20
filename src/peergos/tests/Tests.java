package peergos.tests;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import peergos.corenode.*;
import peergos.crypto.User;
import peergos.server.storage.ContentAddressedStorage;
import peergos.server.storage.RAMStorage;
import peergos.user.*;
import peergos.util.*;

import java.util.*;

public class Tests
{

//    public static void main(String[] args) throws Exception
//    {
//        Args.parse(args);
////        testClass(Components.class);
////        testClass(ErasureCodes.class);
////        testClass(GaloisPolynomial.Test.class);
////        testClass(Crypto.class);
////        testClass(SSL.class);
////        testClass(ErasureHandler.Test.class);
//
//        if (Args.hasArg("local")) {
//            localTests();
//            return;
//        }
//
//        UserContext.Test.setStorageAddress(Args.getArg("clusterAddress", "localhost"));
//        UserContext.Test.setCoreNodeAddress(Args.getArg("coreAddress", "localhost"));
//        String user = Args.hasArg("randomise") ? "Bob" + System.currentTimeMillis() : "Bob";
//        String follower = Args.hasArg("randomise") ? "Alice" + System.currentTimeMillis() : "Alice";
//        UserContext.Test.setUser(user);
//        UserContext.Test.setFollower(follower);
//
//        testClass(UserContext.Test.class);
////        testClass(User.Test.class);
////        testClass(CoreNode.class);
//    }
//
//    private static void testClass(Class c)
//    {
//        Result result = JUnitCore.runClasses(c);
//
//        for (Failure failure : result.getFailures()) {
//            System.out.println(failure.toString());
//        }
//    }
//
//    public static void localTests() {
//        System.out.println("Doing local tests..");
//        try {
//            ContentAddressedStorage dht = new RAMStorage();
//            CoreNode core = SQLiteCoreNode.build(":memory:");
//            contextTests(dht, core);
//        } catch (Throwable e) {
//            e.printStackTrace();
//            System.exit(-1);
//        } finally {
//            System.exit(0);
//        }
//    }
//
//    public static void contextTests(ContentAddressedStorage dht, CoreNode core) throws Exception {
//        String ourname = "Bob";
//        User us = User.generateUserCredentials(ourname, "password");
//        UserContext bob = new UserContext(ourname, us, dht, core);
//
//        String alicesName = "Alice";
//        User them = User.generateUserCredentials(alicesName, "password");
//        UserContext alice = new UserContext(alicesName, them, dht, core);
//
//        if (!bob.isRegistered())
//            if (!bob.register())
//                throw new IllegalStateException("Couldn't register user!");
//        if (!alice.isRegistered())
//            if (!alice.register())
//                throw new IllegalStateException("Couldn't register user!");
//
//        boolean followed = bob.sendFollowRequest(them);
//
//        List<byte[]> reqs = alice.getFollowRequests();
//        assert(reqs.size() == 1);
//        EntryPoint rootEntry = alice.decodeFollowRequest(reqs.get(0));
//        User sharer = (User)rootEntry.pointer.writer;
//
//        // store a chunk in alice's space using the permitted sharing key
//
//        long t1 = System.nanoTime();
//        UserContext.Test.mediumFileTest(us, sharer, bob, alice);
//        long t2 = System.nanoTime();
//        System.out.printf("File test took %d mS\n", (t2 - t1) / 1000000);
//    }
}
