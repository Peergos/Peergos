package peergos.tests;

import peergos.crypto.SSL;
import peergos.crypto.SymmetricKey;
import peergos.crypto.UserPublicKey;
import peergos.crypto.User;
import peergos.user.fs.Chunk;
import peergos.user.fs.EncryptedChunk;
import peergos.storage.net.IP;
import peergos.util.ArrayOps;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.Test;

import java.security.KeyPair;
import java.security.cert.Certificate;

import static junit.framework.Assert.assertTrue;
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
    public void AESEncryption()
    {
        byte[] raw = new byte[1024*1024];
        byte[] iv = new byte[SymmetricKey.IV_SIZE];
        Chunk chunk = new Chunk(raw, SymmetricKey.random());
        byte[] encrypted = chunk.encrypt(iv);
        EncryptedChunk coded = new EncryptedChunk(encrypted);
        byte[] original = coded.decrypt(chunk.getKey(), iv);
        assertTrue(java.util.Arrays.equals(raw, original));
    }

    @Test
    public void SSLStorageCertificateCreationAndValidation()
    {
        try {
            char[] storagePass = "password".toCharArray();
            char[] dirpass = "password".toCharArray();
            KeyPair dirKeys = SSL.loadKeyPair("dir.key", dirpass);
            Certificate[] dirCerts = SSL.getDirectoryServerCertificates();
            // pick one
            Certificate dir = dirCerts[0];
            KeyPair storageKeys = SSL.generateCSR(storagePass, "localhost", "storage.p12", "storage.csr");
            PKCS10CertificationRequest csr = SSL.loadCSR("storage.csr");
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
            KeyPair dirKeys = SSL.loadKeyPair("dir.key", dirpass);
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
            String privFile = "dir.key";
            String csrFile = "dir.csr";
            KeyPair pair = SSL.generateCSR(dirpass, IP.getMyPublicAddress().getHostAddress(), privFile, csrFile);
            long start = System.nanoTime();
            Certificate cert = SSL.signCertificate(csrFile, rootpass, "Directory");
            long end = System.nanoTime();
            System.out.printf("CSR signing took %d mS\n", (end-start)/1000000);
            assertEquals(cert.getPublicKey(), pair.getPublic());
            KeyPair samePair = SSL.loadKeyPair(privFile, dirpass);
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
//        System.out.println(ArrayOps.bytesToHex(raw));
//        System.out.println(ArrayOps.bytesToHex(unsigned));
//        System.out.println(ArrayOps.bytesToHex(decrypted));
//        System.out.println("Signed["+signed.length+"]: "+ArrayOps.bytesToHex(signed));
//        System.out.println("Encryped["+encrypted.length+"]: "+ArrayOps.bytesToHex(encrypted));

        assertEquals("Correct encrypted size", UserPublicKey.RSA_KEY_BITS /8, signed.length);
        assertEquals("Correct encrypted size", UserPublicKey.RSA_KEY_BITS /8, encrypted.length);
        assertEquals("unsigning is the inverse of signing", ArrayOps.bytesToHex(raw), ArrayOps.bytesToHex(unsigned));
        assertEquals("decrypt is the inverse of encrypt", ArrayOps.bytesToHex(raw), ArrayOps.bytesToHex(decrypted));

        UserPublicKey rem = new UserPublicKey(u.getPublicKey());
        byte[] encrypted2 = rem.encryptMessageFor(raw);
        byte[] decrypted2 = u.decryptMessage(encrypted2);
        assertEquals("decrypt is the inverse of recover key -> encrypt", ArrayOps.bytesToHex(raw), ArrayOps.bytesToHex(decrypted2));
        assertEquals("recovered public key and generated are identical", ArrayOps.bytesToHex(u.getPublicKey()), ArrayOps.bytesToHex(rem.getPublicKey()));
    }

    @Test
    public void keyDerivationFromPassword()
    {
        String username = "MyUser";
        String password = "MyPassword";
        // test hashes are identical
        byte[] hash1 = User.hash(username+password);
        byte[] hash2 = User.hash(username+password);
        assertEquals("hashes should be identical", ArrayOps.bytesToHex(hash1), ArrayOps.bytesToHex(hash2));

        // independent logins must be able to decrypt each others encryption
        User u1 = User.generateUserCredentials(username, password);
        User u2 = User.generateUserCredentials(username, password);

        // public keys should be identical
        assertEquals("public keys should be identical", ArrayOps.bytesToHex(u1.getPublicKey()), ArrayOps.bytesToHex(u2.getPublicKey()));

        byte[] raw = "This is some random raw data.".getBytes();
        byte[] signed = u1.signMessage(raw);
        byte[] unsigned = u2.unsignMessage(signed);
        byte[] encrypted = u1.encryptMessageFor(raw);
        byte[] decrypted = u2.decryptMessage(encrypted);
        assertEquals("unsigning is the inverse of signing", ArrayOps.bytesToHex(raw), ArrayOps.bytesToHex(unsigned));
        assertEquals("decrypt is the inverse of encrypt", ArrayOps.bytesToHex(raw), ArrayOps.bytesToHex(decrypted));
    }
}
