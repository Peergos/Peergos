package peergos.tests;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

public class Tests
{

    public static void main(String[] args)
    {
        testClass(Components.class);
        testClass(Crypto.class);
    }

    private static void testClass(Class c)
    {
        Result result = JUnitCore.runClasses(c);

        for (Failure failure : result.getFailures()) {
            System.out.println(failure.toString());
        }
    }
}
