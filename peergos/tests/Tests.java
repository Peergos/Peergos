package peergos.tests;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import peergos.fs.erasure.GaloisPolynomial;

public class Tests
{

    public static void main(String[] args)
    {
<<<<<<< HEAD
        //testClass(Components.class);
        //testClass(Crypto.class);
        testClass(CoreNode.class);
=======
//        testClass(Components.class);
//        testClass(ErasureCodes.class);
        testClass(GaloisPolynomial.Test.class);
//        testClass(Crypto.class);
//        testClass(CoreNode.class);
>>>>>>> 96fa7beb9c093c6262fd40c4dc51f83c00270fbd
    }

    private static void testClass(Class c)
    {
        Result result = JUnitCore.runClasses(c);

        for (Failure failure : result.getFailures()) {
            System.out.println(failure.toString());
        }
    }
}
