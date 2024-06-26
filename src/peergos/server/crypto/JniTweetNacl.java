package peergos.server.crypto;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class JniTweetNacl {

    private JniTweetNacl() {}

    public static JniTweetNacl build() {
        return new JniTweetNacl();
    }

    static {
        String absoluteLibPath = "";
        try {
            new File("native-lib").mkdirs();
            Path libPath = PathUtil.get("native-lib", "libtweetnacl.so");
            if (! libPath.toFile().exists()) {
                byte[] data = Serialize.readFully(JniTweetNacl.class.getResourceAsStream("/" + libPath.toString()));
                Files.write(libPath, data, StandardOpenOption.CREATE);
            }
            absoluteLibPath = libPath.toFile().getAbsolutePath();
            System.loadLibrary("tweetnacl");
        } catch (Throwable t) {
            if ("linux".equalsIgnoreCase(System.getProperty("os.name"))) {
                System.err.println("Couldn't load native crypto library at " + absoluteLibPath + ", using pure Java version...");
                System.err.println("To use the native linux-x86-64 crypto implementation use option -Djava.library.path=native-lib");
            }
            throw new RuntimeException(t);
        }
    }

    public static native int crypto_box_keypair(byte[] y, byte[] x);

    public static native int crypto_box_open(byte[] m, byte[]c, long d, byte[] b, byte[] y, byte[] x);

    public static native int crypto_box(byte[] c, byte[] m, long d, byte[] n, byte[] y, byte[] x);


    public static native int crypto_sign_open(byte[] m, long mlen, byte[] sm, long n, byte[] pk);

    public static native int crypto_sign(byte[] sm, long smlen, byte[] m, long n, byte[] sk);

    public static native int crypto_sign_keypair(byte[] pk, byte[] sk);


    public static native int crypto_secretbox_open(byte[] m, byte[] c, long d, byte[] n, byte[] k);

    public static native int crypto_secretbox(byte[] c, byte[] m, long d, byte[] n, byte[] k);


    public static native int crypto_scalarmult_base(byte[] q, byte[] n);

    public static native int ld32(byte[] b);

    public static class Signer implements Ed25519 {

        private final JniTweetNacl impl;

        public Signer(JniTweetNacl impl) {
            this.impl = impl;
        }

        @Override
        public CompletableFuture<byte[]> crypto_sign_open(byte[] signed, byte[] publicSigningKey) {
            byte[] message = new byte[signed.length];
            int res = impl.crypto_sign_open(message, message.length, signed, signed.length, publicSigningKey);
            if (res != 0)
                throw new TweetNaCl.InvalidSignatureException();
            return Futures.of(Arrays.copyOfRange(message, 0, message.length - TweetNaCl.SIGNATURE_SIZE_BYTES));
        }

        @Override
        public CompletableFuture<byte[]> crypto_sign(byte[] message, byte[] secretSigningKey) {
            byte[] signedMessage = new byte[message.length + TweetNaCl.SIGNATURE_SIZE_BYTES];
            impl.crypto_sign(signedMessage, signedMessage.length, message, message.length, secretSigningKey);
            return Futures.of(signedMessage);
        }

        @Override
        public void crypto_sign_keypair(byte[] pk, byte[] sk) {
            impl.crypto_sign_keypair(pk, sk);
        }
    }

    public static class Symmetric implements peergos.shared.crypto.symmetric.Salsa20Poly1305 {

        private final JniTweetNacl impl;

        public Symmetric(JniTweetNacl impl) {
            this.impl = impl;
        }

        @Override
        public byte[] secretbox(byte[] data, byte[] nonce, byte[] key) {
            byte[] cipherText = new byte[data.length + 32]; // add secret box internal overhead bytes
            byte[] expandedData = new byte[cipherText.length];
            System.arraycopy(data, 0, expandedData, 32, data.length);
            int res = impl.crypto_secretbox(cipherText, expandedData, cipherText.length, nonce, key);
            if (res != 0)
                throw new TweetNaCl.InvalidSignatureException();
            return Arrays.copyOfRange(cipherText, 16, cipherText.length);
        }

        @Override
        public byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key) {
            byte[] message = new byte[cipher.length + TweetNaCl.SECRETBOX_OVERHEAD_BYTES];
            byte[] expandedCipher = new byte[message.length];
            System.arraycopy(cipher, 0, expandedCipher, TweetNaCl.SECRETBOX_OVERHEAD_BYTES, cipher.length);
            int res = impl.crypto_secretbox_open(message, expandedCipher, expandedCipher.length, nonce, key);
            if (res != 0)
                throw new InvalidCipherTextException();
            return Arrays.copyOfRange(message, 32, message.length);
        }

    }
}
