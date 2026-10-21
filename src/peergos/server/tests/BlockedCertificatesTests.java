package peergos.server.tests;

import org.junit.*;
import peergos.server.util.*;

import javax.net.ssl.*;
import java.security.cert.*;

public class BlockedCertificatesTests {

    @Test
    public void trustedRootsAreNotBlocked() {
        X509Certificate[] roots = BlockedCertificates.trustManager().getAcceptedIssuers();
        Assert.assertTrue(roots.length > 0);
        for (X509Certificate root : roots)
            Assert.assertFalse(root.getSubjectX500Principal().toString(), BlockedCertificates.isBlocked(root));
    }

    @Test
    public void installIsIdempotent() throws Exception {
        BlockedCertificates.install();
        SSLContext installed = SSLContext.getDefault();
        BlockedCertificates.install();
        Assert.assertSame(installed, SSLContext.getDefault());
    }
}
