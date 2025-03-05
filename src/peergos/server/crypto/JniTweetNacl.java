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

    private static String canonicaliseArchitecture(String arch) {
        if (arch.startsWith("arm64"))
            return "aarch64";
        if (arch.startsWith("arm"))
            return "arm";
        if (arch.startsWith("x86_64"))
            return "amd64";
        return arch;
    }

    static {
        String absoluteLibPath = "";
        boolean isLinux = "linux".equalsIgnoreCase(System.getProperty("os.name"));
        boolean isAndroid = "The Android Project".equals(System.getProperty("java.vm.vendor"));
        if (isAndroid) {
            System.loadLibrary("tweetnacl");
        } else {
            String arch = canonicaliseArchitecture(System.getProperty("os.arch"));
            try {
                new File("native-lib").mkdirs();
                Path writeLibPath = PathUtil.get("native-lib", "libtweetnacl.so");
                if (! writeLibPath.toFile().exists()) {
                    Path libPath = PathUtil.get("native-lib", "linux", arch, "libtweetnacl.so");
                    byte[] data = Serialize.readFully(JniTweetNacl.class.getResourceAsStream("/" + libPath));
                    Files.write(writeLibPath, data, StandardOpenOption.CREATE);
                }
                absoluteLibPath = writeLibPath.toFile().getAbsolutePath();
                System.loadLibrary("tweetnacl");
            } catch (Throwable t) {
                if (isLinux) {
                    System.err.println("Couldn't load native crypto library at " + absoluteLibPath + ", using pure Java version...");
                    System.err.println("To use the native Linux crypto implementation use option -Djava.library.path=native-lib");
                }
                throw new RuntimeException(t);
            }
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
