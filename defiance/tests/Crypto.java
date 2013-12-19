package defiance.tests;

import defiance.crypto.PublicKey;
import defiance.crypto.User;
import defiance.util.Arrays;
import org.junit.Test;

import static junit.framework.TestCase.assertEquals;

public class Crypto
{
    public void cryptoCapabilities()
    {
        boolean res = User.testEncryptionCapabilities();
        User.enumerateAllCryptoAlgorithmsAvailable();
        assertEquals("Crypto capabilities", true, res);
    }

    @Test
    public void userCrypto()
    {
        User u = User.random();
        byte[] raw = "This is the raw data.".getBytes();
        byte[] signed = u.signMessage(raw);
        byte[] unsigned = u.unsignMessage(signed);
        byte[] encrypted = u.encryptMessageFor(raw);
        byte[] decrypted = u.decryptMessage(encrypted);
//        System.out.println(Arrays.bytesToHex(raw));
//        System.out.println(Arrays.bytesToHex(unsigned));
//        System.out.println(Arrays.bytesToHex(decrypted));
//        System.out.println("Signed["+signed.length+"]: "+Arrays.bytesToHex(signed));
//        System.out.println("Encryped["+encrypted.length+"]: "+Arrays.bytesToHex(encrypted));

        assertEquals("Correct encrypted size", PublicKey.RSA_KEY_SIZE/8, signed.length);
        assertEquals("Correct encrypted size", PublicKey.RSA_KEY_SIZE/8, encrypted.length);
        assertEquals("unsigning is the inverse of signing", Arrays.bytesToHex(raw), Arrays.bytesToHex(unsigned));
        assertEquals("decrypt is the inverse of encrypt", Arrays.bytesToHex(raw), Arrays.bytesToHex(decrypted));

        PublicKey rem = new PublicKey(u.getPublicKey());
        byte[] encrypted2 = rem.encryptMessageFor(raw);
        byte[] decrypted2 = u.decryptMessage(encrypted2);
        assertEquals("decrypt is the inverse of recover key -> encrypt", Arrays.bytesToHex(raw), Arrays.bytesToHex(decrypted2));
        assertEquals("recovered public key and generated are identical", Arrays.bytesToHex(u.getPublicKey()), Arrays.bytesToHex(rem.getPublicKey()));
    }

    @Test
    public void keyDerivationFromPassword()
    {
        String username = "MyUser";
        String password = "MyPassword";
        // test hashes are identical
        byte[] hash1 = User.hash(username+password);
        byte[] hash2 = User.hash(username+password);
        assertEquals("hashes should be identical", Arrays.bytesToHex(hash1), Arrays.bytesToHex(hash2));

        // independent logins must be able to decrypt each others encryption
        User u1 = User.generateUserCredentials(username, password);
        User u2 = User.generateUserCredentials(username, password);

        // public keys should be identical
        assertEquals("public keys should be identical", Arrays.bytesToHex(u1.getPublicKey()), Arrays.bytesToHex(u2.getPublicKey()));

        byte[] raw = "This is some random raw data.".getBytes();
        byte[] signed = u1.signMessage(raw);
        byte[] unsigned = u2.unsignMessage(signed);
        byte[] encrypted = u1.encryptMessageFor(raw);
        byte[] decrypted = u2.decryptMessage(encrypted);
        assertEquals("unsigning is the inverse of signing", Arrays.bytesToHex(raw), Arrays.bytesToHex(unsigned));
        assertEquals("decrypt is the inverse of encrypt", Arrays.bytesToHex(raw), Arrays.bytesToHex(decrypted));
    }
}
