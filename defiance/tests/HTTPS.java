package defiance.tests;

import defiance.net.HTTPSMessenger;
import org.junit.Test;

import java.io.IOException;
import java.util.logging.Logger;

public class HTTPS {
    @Test
    public void test()
    {
        try {
            HTTPSMessenger m = new HTTPSMessenger(8080, Logger.getLogger("test"));
            m.join(null, 0);
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
