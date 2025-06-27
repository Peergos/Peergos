package peergos.server.tests.fips203.provider;

import org.junit.Before;
import org.junit.Test;
import peergos.server.crypto.asymmetric.mlkem.fips203.provider.MimicloneSecurityProvider;

import java.security.Provider;
import java.security.Security;

import static org.junit.Assert.assertNotNull;

public class MimicloneSecurityProviderTests {

    @Before
    public void setUp() {
        new MimicloneSecurityProvider().install();
    }

    @Test
    public void testSecurityProviders() {

        Provider mimicloneProvider = Security.getProvider(MimicloneSecurityProvider.PROVIDER_NAME);
        assertNotNull(mimicloneProvider);

        for (Provider provider : Security.getProviders()) {
            System.out.printf("Security Provider: [%s]:%n", provider.getName());
            for (Provider.Service service : provider.getServices()) {
               System.out.printf(" --> [%s] Service [%s]%n", service.getType(), service.getAlgorithm());
            }
        }

    }

}
