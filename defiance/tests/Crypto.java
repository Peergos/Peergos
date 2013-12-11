package defiance.tests;

import defiance.crypto.RemoteUser;
import defiance.crypto.User;
import defiance.util.Arrays;
import junit.framework.Assert;
import org.junit.Test;

import static junit.framework.TestCase.assertEquals;

public class Crypto
{
    @Test
    public void cryptoCapabilities()
    {
        boolean res = User.testEncryptionCapabilities();
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

        assertEquals("Correct encrypted size", RemoteUser.RSA_KEY_SIZE/8, signed.length);
        assertEquals("Correct encrypted size", RemoteUser.RSA_KEY_SIZE/8, encrypted.length);
        assertEquals("unsigning is the inverse of signing", Arrays.bytesToHex(raw), Arrays.bytesToHex(unsigned));
        assertEquals("decrypt is the inverse of encrypt", Arrays.bytesToHex(raw), Arrays.bytesToHex(decrypted));

        RemoteUser rem = new RemoteUser(u.getPublicKey());
        byte[] encrypted2 = rem.encryptMessageFor(raw);
        byte[] decrypted2 = u.decryptMessage(encrypted2);
        assertEquals("decrypt is the inverse of recover key -> encrypt", Arrays.bytesToHex(raw), Arrays.bytesToHex(decrypted2));
    }
}
