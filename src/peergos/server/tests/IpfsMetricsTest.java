package peergos.server.tests;

import org.junit.*;
import peergos.server.storage.*;
import peergos.server.util.*;

import java.util.*;

public class IpfsMetricsTest {

    @Test
    public void enableMetrics() {
        Args a = Args.parse(new String[]{"-collect-metrics", "true",
                "-metrics.address", "192.168.10.1",
                "-ipfs.metrics.port", "9000",
                "-proxy-target", "/ip4/127.0.0.1/tcp/8000",
                "-ipfs-api-address", "/ip4/127.0.0.1/tcp/5001",
                "ipfs-gateway-address", "/ip4/127.0.0.1/tcp/8080",
        }, Optional.empty(), false);
        IpfsWrapper.IpfsConfigParams conf = IpfsWrapper.buildConfig(a);
        Assert.assertTrue(conf.enableMetrics);
    }
}
