package peergos.server.crypto.asymmetric.mlkem;

import peergos.server.crypto.asymmetric.mlkem.fips203.FIPS203Exception;

import java.security.DrbgParameters;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.SecureRandomParameters;

public class MlkemSecureRandom {
    // Secure RBG algorithm set name
    private static final String SECURE_RBG_ALGO = "DRBG";

    public static SecureRandom getSecureRandom(int minSecurityStrength) {
        try {
            try {
                // Create secure random parameters
                SecureRandomParameters secureParams = DrbgParameters.instantiation(
                        minSecurityStrength,
                        DrbgParameters.Capability.PR_AND_RESEED,
                        null);

                // Create sure random instance
                return SecureRandom.getInstance(SECURE_RBG_ALGO, secureParams);
            } catch (Exception e) {
                return SecureRandom.getInstanceStrong();
            }
        } catch (NoSuchAlgorithmException e) {
            throw new FIPS203Exception(e.getMessage());
        } catch (Throwable e) {
            // Android < 15
            return new SecureRandom();
        }
    }
}
