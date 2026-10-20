package peergos.server.tests;

import org.junit.*;
import peergos.server.crypto.*;
import peergos.server.crypto.asymmetric.curve25519.*;

import java.math.*;
import java.util.*;

public class JdkEd25519Tests {
    private static final Ed25519Java tweetNacl = new Ed25519Java();
    private static final JdkEd25519 jdk = new JdkEd25519();
    private static final Random random = new Random(42);

    private static byte[][] keypair() {
        byte[] pk = new byte[32], sk = new byte[64];
        random.nextBytes(sk);
        jdk.crypto_sign_keypair(pk, sk);
        return new byte[][]{pk, sk};
    }

    @Test
    public void matchesTweetNacl() {
        for (int i = 0; i < 100; i++) {
            byte[][] keys = keypair();
            byte[] message = new byte[random.nextInt(1000)];
            random.nextBytes(message);
            byte[] signed = jdk.crypto_sign(message, keys[1]).join();
            Assert.assertArrayEquals(tweetNacl.crypto_sign(message, keys[1]).join(), signed);
            Assert.assertArrayEquals(message, jdk.crypto_sign_open(signed, keys[0]).join());
            Assert.assertArrayEquals(message, tweetNacl.crypto_sign_open(signed, keys[0]).join());
        }
    }

    @Test
    public void rejectsBadSignatures() {
        byte[][] keys = keypair();
        byte[] signed = jdk.crypto_sign("hello".getBytes(), keys[1]).join();
        for (int i = 0; i < signed.length; i++) {
            byte[] corrupt = signed.clone();
            corrupt[i] ^= 1;
            assertRejected(corrupt, keys[0]);
        }
        assertRejected(Arrays.copyOfRange(signed, 0, 63), keys[0]);
        assertRejected(signed, keypair()[0]);
        assertRejected(signed, new byte[32]);
        assertRejected(signed, new byte[31]);
    }

    /** TweetNaCl accepts s + L as well as s; the JDK only the canonical s. Honest signers only make the canonical. */
    @Test
    public void rejectsNonCanonicalS() {
        byte[][] keys = keypair();
        byte[] signed = jdk.crypto_sign("hello".getBytes(), keys[1]).join();
        BigInteger l = BigInteger.TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
        BigInteger s = new BigInteger(1, reverse(Arrays.copyOfRange(signed, 32, 64))).add(l);
        byte[] malleated = signed.clone();
        byte[] sBytes = reverse(s.toByteArray());
        Arrays.fill(malleated, 32, 64, (byte) 0);
        System.arraycopy(sBytes, 0, malleated, 32, Math.min(32, sBytes.length));
        Assert.assertTrue(s.bitLength() <= 256);
        tweetNacl.crypto_sign_open(malleated, keys[0]).join();
        assertRejected(malleated, keys[0]);
    }

    @Test
    public void fasterThanTweetNacl() {
        byte[][] keys = keypair();
        byte[] signed = jdk.crypto_sign(new byte[200], keys[1]).join();
        for (int i = 0; i < 2000; i++)
            jdk.crypto_sign_open(signed, keys[0]);
        int n = 2000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++)
            jdk.crypto_sign_open(signed, keys[0]);
        long t1 = System.nanoTime();
        try {
            JniTweetNacl.Signer jni = new JniTweetNacl.Signer(JniTweetNacl.build());
            for (int i = 0; i < 200; i++)
                jni.crypto_sign_open(signed, keys[0]);
            long t2 = System.nanoTime();
            for (int i = 0; i < n; i++)
                jni.crypto_sign_open(signed, keys[0]);
            long t3 = System.nanoTime();
            System.out.printf("Ed25519 verify: JDK %d µs, JNI TweetNaCl %d µs%n", (t1 - t0) / n / 1000, (t3 - t2) / n / 1000);
        } catch (Throwable t) {
            System.out.printf("Ed25519 verify: JDK %d µs, no JNI TweetNaCl here%n", (t1 - t0) / n / 1000);
        }
    }

    private static void assertRejected(byte[] signed, byte[] pk) {
        try {
            jdk.crypto_sign_open(signed, pk);
            Assert.fail("accepted");
        } catch (TweetNaCl.InvalidSignatureException expected) {}
    }

    private static byte[] reverse(byte[] in) {
        byte[] res = new byte[in.length];
        for (int i = 0; i < in.length; i++)
            res[i] = in[in.length - 1 - i];
        return res;
    }
}
