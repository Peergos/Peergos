package peergos.tests;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import peergos.user.UserContext;
import peergos.util.Args;

import java.io.File;
import java.io.IOException;

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

        UserContext.Test.setStorageAddress(Args.getParameter("clusterAddress", "localhost"));
        UserContext.Test.setCoreNodeAddress(Args.getParameter("coreAddress", "localhost"));
        String user = Args.hasOption("randomise") ? "Bob" + System.currentTimeMillis() : "Bob";
        String follower = Args.hasOption("randomise") ? "Alice" + System.currentTimeMillis() : "Alice";
        UserContext.Test.setUser(user);
        UserContext.Test.setFollower(follower);

        if (!Args.hasOption("randomise")) {
            UserContext.Test.ensureKeyPairForUser("Alice", new File(Args.getParameter("firstKeyPairFile", "cache.1.key")));
            UserContext.Test.ensureKeyPairForUser("Bob", new File(Args.getParameter("secondKeyPairFile", "cache.2.key")));
        }
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
}
