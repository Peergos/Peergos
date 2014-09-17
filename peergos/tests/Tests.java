package peergos.tests;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import peergos.storage.net.IP;
import peergos.user.UserContext;

import java.io.IOException;

public class Tests
{

    public static void main(String[] args) throws IOException
    {
        String clusterAddress = IP.getMyPublicAddress().getHostAddress();
        String coreNodeAddress, storageAddress;
        if (args.length >0)
            coreNodeAddress = args[0];
        else
            coreNodeAddress = clusterAddress;

        if (args.length >1)
            storageAddress = args[1];
        else
            storageAddress = clusterAddress;



//        testClass(Components.class);
//        testClass(ErasureCodes.class);
//        testClass(GaloisPolynomial.Test.class);
//        testClass(Crypto.class);
//        testClass(UserContext.Test.class);

        UserContext.Test.setCoreNodeAddress(coreNodeAddress);
        UserContext.Test.setStorageAddress(storageAddress);
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
