package peergos.server.crypto;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class JniTweetNacl {
    static {
        try {
            new File("native-lib").mkdirs();
            Path libPath = Paths.get("native-lib", "libtweetnacl.so");
            if (! libPath.toFile().exists()) {
                byte[] data = Serialize.readFully(JniTweetNacl.class.getResourceAsStream("/" + libPath.toString()));
                Files.write(libPath, data, StandardOpenOption.CREATE);
            }
            String absoluteLibPath = libPath.toFile().getAbsolutePath();
            System.out.println("Trying to load native crypto library at " + absoluteLibPath);
            System.setProperty("java.library.path", absoluteLibPath);
            System.loadLibrary("tweetnacl");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static native int crypto_box_keypair(byte[] y, byte[] x);

    public static native int crypto_box_open(byte[] m, byte[]c, long d, byte[] b, byte[] y, byte[] x);

    public static native int crypto_box(byte[] c, byte[] m, long d, byte[] n, byte[] y, byte[] x);


    public static native int crypto_sign_open(byte[] m, long mlen, byte[] sm, long n, byte[] pk);

    public static native int crypto_sign(byte[] sm, long smlen, byte[] m, long n, byte[] sk);

    public static native int crypto_sign_keypair(byte[] pk, byte[] sk);


    public static native int crypto_scalarmult_base(byte[] q, byte[] n);

    public static native int ld32(byte[] b);

    public static class Signer implements Ed25519 {

        private final JniTweetNacl impl;

        public Signer(JniTweetNacl impl) {
            this.impl = impl;
        }

        @Override
        public byte[] crypto_sign_open(byte[] signed, byte[] publicSigningKey) {
            byte[] message = new byte[signed.length];
            int res = impl.crypto_sign_open(message, message.length, signed, signed.length, publicSigningKey);
            if (res != 0)
                throw new TweetNaCl.InvalidSignatureException();
            return Arrays.copyOfRange(message, 0, message.length - TweetNaCl.SIGNATURE_SIZE_BYTES);
        }

        @Override
        public byte[] crypto_sign(byte[] message, byte[] secretSigningKey) {
            byte[] signedMessage = new byte[message.length + TweetNaCl.SIGNATURE_SIZE_BYTES];
            impl.crypto_sign(signedMessage, signedMessage.length, message, message.length, secretSigningKey);
            return signedMessage;
        }

        @Override
        public void crypto_sign_keypair(byte[] pk, byte[] sk) {
            impl.crypto_sign_keypair(pk, sk);
        }
    }
}
