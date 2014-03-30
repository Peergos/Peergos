package defiance.tests;

import defiance.crypto.SSL;
import defiance.crypto.UserPublicKey;
import defiance.crypto.User;
import defiance.net.IP;
import defiance.util.Arrays;
import junit.framework.Assert;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.Test;

import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

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
    public void SSLStorageCertificateCreationAndValidation()
    {
        try {
            char[] storagePass = "password".toCharArray();
            char[] dirpass = "password".toCharArray();
            KeyPair dirKeys = SSL.loadKeyPair("dirKeys.pem", dirpass);
            Certificate[] dirCerts = SSL.getDirectoryServerCertificates();
            // pick one
            Certificate dir = dirCerts[0];
            KeyPair storageKeys = SSL.generateCSR(storagePass, "storageKeys.pem", "storageCSR.pem");
            PKCS10CertificationRequest csr = SSL.loadCSR("storageCSR.pem");
            Certificate signed = SSL.signCertificate(csr, dirKeys.getPrivate(), IP.getMyPublicAddress().getHostAddress());
            // verify storage with dir key
            signed.verify(dir.getPublicKey());
            // verify dir with root key
            dir.verify(SSL.getRootCertificate().getPublicKey());
        } catch (Exception e)
        {
            e.printStackTrace();
            assertEquals(true, false);
        }
    }

    @Test
    public void SSLDirectoryCertificateValidation()
    {
        try {
            char[] rootpass = "password".toCharArray();
            char[] dirpass = "password".toCharArray();
            KeyPair dirKeys = SSL.loadKeyPair("dirKeys.pem", dirpass);
            Certificate[] dirCerts = SSL.getDirectoryServerCertificates();
            // verify certificate using RootCA and directly using public rootKey
            for (Certificate cert: dirCerts) {
                cert.verify(SSL.getRootKeyStore(rootpass).getCertificate("rootCA").getPublicKey());
                cert.verify(SSL.getRootCertificate().getPublicKey());
                System.out.println("Verified "+ SSL.getCommonName(cert));
            }
            System.out.printf("Verified %s directory server certificate(s) against rootCA\n", dirCerts.length);
        } catch (Exception e)
        {
            e.printStackTrace();
            assertEquals(true, false);
        }
    }

    // overwrites directory server keys
    public void SSLCertificateGenerationAndSigning()
    {
        try {
            char[] rootpass = "password".toCharArray();
            char[] dirpass = "password".toCharArray();
            KeyPair pair = SSL.generateCSR(dirpass, "dirKeys.pem", "dirCSR.pem");
            long start = System.nanoTime();
            Certificate cert = SSL.signDirectoryCertificate("dirCSR.pem", rootpass);
            long end = System.nanoTime();
            System.out.printf("CSR signing took %d mS\n", (end-start)/1000000);
            assertEquals(cert.getPublicKey(), pair.getPublic());
            KeyPair samePair = SSL.loadKeyPair("dirKeys.pem", dirpass);
            assertEquals(pair.getPrivate(), samePair.getPrivate());
            // verify certificate using RootCA and directly using public rootKey
            cert.verify(SSL.getRootKeyStore(rootpass).getCertificate("rootCA").getPublicKey());
            cert.verify(SSL.getRootCertificate().getPublicKey());

        } catch (Exception e)
        {
            e.printStackTrace();
            assertEquals(true, false);
        }
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

        assertEquals("Correct encrypted size", UserPublicKey.RSA_KEY_SIZE/8, signed.length);
        assertEquals("Correct encrypted size", UserPublicKey.RSA_KEY_SIZE/8, encrypted.length);
        assertEquals("unsigning is the inverse of signing", Arrays.bytesToHex(raw), Arrays.bytesToHex(unsigned));
        assertEquals("decrypt is the inverse of encrypt", Arrays.bytesToHex(raw), Arrays.bytesToHex(decrypted));

        UserPublicKey rem = new UserPublicKey(u.getPublicKey());
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
