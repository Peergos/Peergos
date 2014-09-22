package peergos.tests;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import peergos.crypto.SSL;
import peergos.storage.net.IP;
import peergos.user.UserContext;
import peergos.util.Args;

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
//        testClass(UserContext.Test.class);

        UserContext.Test.setStorageAddress(Args.getParameter("clusterAddress", IP.getMyPublicAddress().getHostAddress()));
        UserContext.Test.setCoreNodeAddress(Args.getParameter("coreAddress", IP.getMyPublicAddress().getHostAddress()));
//        testClass(SSL.class);
        testClass(UserContext.Test.class);
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
