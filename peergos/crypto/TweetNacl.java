package peergos.crypto;

// Copyright (c) 2014 Tom Zhou<iwebpp@gmail.com>

import java.io.UnsupportedEncodingException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/*
 * @description
 *   TweetNacl.c Java porting
 * */
public final class TweetNacl {

    private final static String TAG = "TweetNacl";

    /*
     * @description
     *   Box algorithm, Public-key authenticated encryption
     * */
    public static final class Box {

        private final static String TAG = "Box";

        private AtomicLong nonce;

        private byte [] theirPublicKey;
        private byte [] mySecretKey;
        private byte [] sharedKey;

        public Box(byte [] theirPublicKey, byte [] mySecretKey) {
            this(theirPublicKey, mySecretKey, 68);
        }

        public Box(byte [] theirPublicKey, byte [] mySecretKey, long nonce) {
            this.theirPublicKey = theirPublicKey;
            this.mySecretKey = mySecretKey;

            this.nonce = new AtomicLong(nonce);

            // generate pre-computed shared key
            before();
        }

        public void setNonce(long nonce) {
            this.nonce.set(nonce);
        }
        public long getNonce() {
            return this.nonce.get();
        }
        public long incrNonce() {
            return this.nonce.incrementAndGet();
        }
        private byte[] generateNonce() {
            // generate nonce
            long nonce = this.nonce.get();

            byte [] n = new byte[nonceLength];
            for (int i = 0; i < nonceLength; i += 8) {
                n[i+0] = (byte) (nonce>>> 0);
                n[i+1] = (byte) (nonce>>> 8);
                n[i+2] = (byte) (nonce>>>16);
                n[i+3] = (byte) (nonce>>>24);
                n[i+4] = (byte) (nonce>>>32);
                n[i+5] = (byte) (nonce>>>40);
                n[i+6] = (byte) (nonce>>>48);
                n[i+7] = (byte) (nonce>>>56);
            }

            return n;
        }

        /*
         * @description
         *   Encrypt and authenticates message using peer's public key,
         *   our secret key, and the given nonce, which must be unique
         *   for each distinct message for a key pair.
         *
         *   Returns an encrypted and authenticated message,
         *   which is nacl.box.overheadLength longer than the original message.
         * */
        ///public byte_buf_t box(byte [] message) {
        public byte [] box(byte [] message) {

            // check message
            if (!(message!=null && message.length>0))
                return null;

            // generate nonce
            byte [] n = generateNonce();

            // message buffer
            byte [] m = new byte[message.length + zerobytesLength];

            // cipher buffer
            byte [] c = new byte[m.length];

            for (int i = 0; i < message.length; i ++)
                m[i+zerobytesLength] = message[i];

            if (0 != crypto_box(c, m, m.length, n, theirPublicKey, mySecretKey))
                return null;

            // wrap byte_buf_t on c offset@boxzerobytesLength
            ///return new byte_buf_t(c, boxzerobytesLength, c.length-boxzerobytesLength);
            byte [] ret = new byte[c.length-boxzerobytesLength];

            for (int i = 0; i < ret.length; i ++)
                ret[i] = c[i+boxzerobytesLength];

            return ret;
        }

        /*
         * @description
         *   Authenticates and decrypts the given box with peer's public key,
         *   our secret key, and the given nonce.
         *
         *   Returns the original message, or null if authentication fails.
         * */
        public byte [] open(byte [] box) {
            // check message
            if (!(box!=null && box.length>boxzerobytesLength))
                return null;

            // generate nonce
            byte [] n = generateNonce();

            // cipher buffer
            byte [] c = new byte[box.length + boxzerobytesLength];

            // message buffer
            byte [] m = new byte[c.length];

            for (int i = 0; i < box.length; i++)
                c[i+boxzerobytesLength] = box[i];

            if (0 != crypto_box_open(m, c, c.length, n, theirPublicKey, mySecretKey))
                return null;

            // wrap byte_buf_t on m offset@zerobytesLength
            ///return new byte_buf_t(m, zerobytesLength, m.length-zerobytesLength);
            byte [] ret = new byte[m.length-zerobytesLength];

            for (int i = 0; i < ret.length; i ++)
                ret[i] = m[i+zerobytesLength];

            return ret;
        }

        /*
         * @description
         *   Returns a precomputed shared key
         *   which can be used in nacl.box.after and nacl.box.open.after.
         * */
        public byte [] before() {
            if (this.sharedKey == null) {
                this.sharedKey = new byte[sharedKeyLength];
                crypto_box_beforenm(this.sharedKey, this.theirPublicKey, this.mySecretKey);
            }

            return this.sharedKey;
        }

        /*
         * @description
         *   Same as nacl.box, but uses a shared key precomputed with nacl.box.before.
         * */
        public byte [] after(byte [] message) {
            // check message
            if (!(message!=null && message.length>0))
                return null;

            // generate nonce
            byte [] n = generateNonce();

            // message buffer
            byte [] m = new byte[message.length + zerobytesLength];

            // cipher buffer
            byte [] c = new byte[m.length];

            for (int i = 0; i < message.length; i ++)
                m[i+zerobytesLength] = message[i];

            if (0 != crypto_box_afternm(c, m, m.length, n, sharedKey))
                return null;

            // wrap byte_buf_t on c offset@boxzerobytesLength
            ///return new byte_buf_t(c, boxzerobytesLength, c.length-boxzerobytesLength);
            byte [] ret = new byte[c.length-boxzerobytesLength];

            for (int i = 0; i < ret.length; i ++)
                ret[i] = c[i+boxzerobytesLength];

            return ret;
        }

        /*
         * @description
         *   Same as nacl.box.open,
         *   but uses a shared key pre-computed with nacl.box.before.
         * */
        public byte [] open_after(byte [] box) {
            // check message
            if (!(box!=null && box.length>boxzerobytesLength))
                return null;

            // generate nonce
            byte [] n = generateNonce();

            // cipher buffer
            byte [] c = new byte[box.length + boxzerobytesLength];

            // message buffer
            byte [] m = new byte[c.length];

            for (int i = 0; i < box.length; i++)
                c[i+boxzerobytesLength] = box[i];

            if (crypto_box_open_afternm(m, c, c.length, n, sharedKey) != 0)
                return null;

            // wrap byte_buf_t on m offset@zerobytesLength
            ///return new byte_buf_t(m, zerobytesLength, m.length-zerobytesLength);
            byte [] ret = new byte[m.length-zerobytesLength];

            for (int i = 0; i < ret.length; i ++)
                ret[i] = m[i+zerobytesLength];

            return ret;
        }

        /*
         * @description
         *   Length of public key in bytes.
         * */
        public static final int publicKeyLength = 32;

        /*
         * @description
         *   Length of secret key in bytes.
         * */
        public static final int secretKeyLength = 32;

        /*
         * @description
         *   Length of precomputed shared key in bytes.
         * */
        public static final int sharedKeyLength = 32;

        /*
         * @description
         *   Length of nonce in bytes.
         * */
        public static final int nonceLength     = 24;

        /*
         * @description
         *   zero bytes in case box
         * */
        public static final int zerobytesLength    = 32;
        /*
         * @description
         *   zero bytes in case open box
         * */
        public static final int boxzerobytesLength = 16;

        /*
         * @description
         *   Length of overhead added to box compared to original message.
         * */
        public static final int overheadLength  = 16;

        public static class KeyPair {
            private byte [] publicKey;
            private byte [] secretKey;

            public KeyPair() {
                publicKey = new byte[publicKeyLength];
                secretKey = new byte[secretKeyLength];
            }

            public byte [] getPublicKey() {
                return publicKey;
            }

            public byte [] getSecretKey() {
                return secretKey;
            }
        }

        /*
         * @description
         *   Generates a new random key pair for box and
         *   returns it as an object with publicKey and secretKey members:
         * */
        public static KeyPair keyPair() {
            KeyPair kp = new KeyPair();

            crypto_box_keypair(kp.getPublicKey(), kp.getSecretKey());
            return kp;
        }

        public static KeyPair keyPair_fromSecretKey(byte [] secretKey) {
            KeyPair kp = new KeyPair();
            byte [] sk = kp.getSecretKey();
            byte [] pk = kp.getPublicKey();

            // copy sk
            for (int i = 0; i < sk.length; i ++)
                sk[i] = secretKey[i];

            crypto_scalarmult_base(pk, sk);
            return kp;
        }

    }

    /*
     * @description
     *   Secret Box algorithm, secret key
     * */
    public static class SecretBox {

        private final static String TAG = "SecretBox";

        private AtomicLong nonce;

        private byte [] key;

        public SecretBox(byte [] key) {
            this(key, 68);
        }

        public SecretBox(byte [] key, long nonce) {
            this.key = key;

            this.nonce = new AtomicLong(nonce);
        }

        public void setNonce(long nonce) {
            this.nonce.set(nonce);
        }
        public long getNonce() {
            return this.nonce.get();
        }
        public long incrNonce() {
            return this.nonce.incrementAndGet();
        }
        private byte[] generateNonce() {
            // generate nonce
            long nonce = this.nonce.get();

            byte [] n = new byte[nonceLength];
            for (int i = 0; i < nonceLength; i += 8) {
                n[i+0] = (byte) (nonce>>> 0);
                n[i+1] = (byte) (nonce>>> 8);
                n[i+2] = (byte) (nonce>>>16);
                n[i+3] = (byte) (nonce>>>24);
                n[i+4] = (byte) (nonce>>>32);
                n[i+5] = (byte) (nonce>>>40);
                n[i+6] = (byte) (nonce>>>48);
                n[i+7] = (byte) (nonce>>>56);
            }

            return n;
        }

        /*
         * @description
         *   Encrypt and authenticates message using the key and the nonce.
         *   The nonce must be unique for each distinct message for this key.
         *
         *   Returns an encrypted and authenticated message,
         *   which is nacl.secretbox.overheadLength longer than the original message.
         * */
        ///public byte_buf_t box(byte [] message) {
        public byte [] box(byte [] message) {
            // check message
            if (!(message!=null && message.length>0))
                return null;

            // generate nonce
            byte [] n = generateNonce();

            // message buffer
            byte [] m = new byte[message.length + zerobytesLength];

            // cipher buffer
            byte [] c = new byte[m.length];

            for (int i = 0; i < message.length; i ++)
                m[i+zerobytesLength] = message[i];

            if (0 != crypto_secretbox(c, m, m.length, n, key))
                return null;

            // TBD optimizing ...
            // wrap byte_buf_t on c offset@boxzerobytesLength
            ///return new byte_buf_t(c, boxzerobytesLength, c.length-boxzerobytesLength);
            byte [] ret = new byte[c.length-boxzerobytesLength];

            for (int i = 0; i < ret.length; i ++)
                ret[i] = c[i+boxzerobytesLength];

            return ret;
        }

        /*
         * @description
         *   Authenticates and decrypts the given secret box
         *   using the key and the nonce.
         *
         *   Returns the original message, or null if authentication fails.
         * */
        public byte [] open(byte [] box) {
            // check message
            if (!(box!=null && box.length>boxzerobytesLength))
                return null;

            // generate nonce
            byte [] n = generateNonce();

            // cipher buffer
            byte [] c = new byte[box.length + boxzerobytesLength];

            // message buffer
            byte [] m = new byte[c.length];

            for (int i = 0; i < box.length; i++)
                c[i+boxzerobytesLength] = box[i];

            if (0 != crypto_secretbox_open(m, c, c.length, n, key))
                return null;

            // wrap byte_buf_t on m offset@zerobytesLength
            ///return new byte_buf_t(m, zerobytesLength, m.length-zerobytesLength);
            byte [] ret = new byte[m.length-zerobytesLength];

            for (int i = 0; i < ret.length; i ++)
                ret[i] = m[i+zerobytesLength];

            return ret;
        }

        /*
         * @description
         *   Length of key in bytes.
         * */
        public static final int keyLength      = 32;

        /*
         * @description
         *   Length of nonce in bytes.
         * */
        public static final int nonceLength    = 24;

        /*
         * @description
         *   Length of overhead added to secret box compared to original message.
         * */
        public static final int overheadLength = 16;

        /*
         * @description
         *   zero bytes in case box
         * */
        public static final int zerobytesLength    = 32;
        /*
         * @description
         *   zero bytes in case open box
         * */
        public static final int boxzerobytesLength = 16;

    }

    /*
     * @description
     *   Scalar multiplication, Implements curve25519.
     * */
    public static final class ScalarMult {

        private final static String TAG = "ScalarMult";

        /*
         * @description
         *   Multiplies an integer n by a group element p and
         *   returns the resulting group element.
         * */
        public static byte [] scalseMult(byte [] n, byte [] p) {
            if (!(n.length==scalarLength && p.length==groupElementLength))
                return null;

            byte [] q = new byte [scalarLength];

            crypto_scalarmult(q, n, p);

            return q;
        }

        /*
         * @description
         *   Multiplies an integer n by a standard group element and
         *   returns the resulting group element.
         * */
        public static byte [] scalseMult_base(byte [] n) {
            if (!(n.length==scalarLength))
                return null;

            byte [] q = new byte [scalarLength];

            crypto_scalarmult_base(q, n);

            return q;
        }

        /*
         * @description
         *   Length of scalar in bytes.
         * */
        public static final int scalarLength        = 32;

        /*
         * @description
         *   Length of group element in bytes.
         * */
        public static final int groupElementLength  = 32;

    }


    /*
     * @description
     *   Hash algorithm, Implements SHA-512.
     * */
    public static final class Hash {

        private final static String TAG = "Hash";

        /*
         * @description
         *   Returns SHA-512 hash of the message.
         * */
        public static byte[] sha512(byte [] message) {
            if (!(message!=null && message.length>0))
                return null;

            byte [] out = new byte[hashLength];

            crypto_hash(out, message);

            return out;
        }
        public static byte[] sha512(String message) throws UnsupportedEncodingException {
            return sha512(message.getBytes("utf-8"));
        }

        /*
         * @description
         *   Length of hash in bytes.
         * */
        public static final int hashLength       = 64;

    }


    /*
     * @description
     *   Signature algorithm, Implements ed25519.
     * */
    public static final class Signature {

        private final static String TAG = "Signature";

        private byte [] theirPublicKey;
        private byte [] mySecretKey;

        public Signature(byte [] theirPublicKey, byte [] mySecretKey) {
            this.theirPublicKey = theirPublicKey;
            this.mySecretKey = mySecretKey;
        }

        /*
         * @description
         *   Signs the message using the secret key and returns a signed message.
         * */
        public byte [] sign(byte [] message) {
            // signed message
            byte [] sm = new byte[message.length + signatureLength];

            crypto_sign(sm, -1, message, message.length, mySecretKey);

            return sm;
        }

        /*
         * @description
         *   Verifies the signed message and returns the message without signature.
         *   Returns null if verification failed.
         * */
        public byte [] open(byte [] signedMessage) {
            // check sm length
            if (!(signedMessage!=null && signedMessage.length>signatureLength))
                return null;

            // temp buffer
            byte [] tmp = new byte[signedMessage.length];

            if (0 != crypto_sign_open(tmp, -1, signedMessage, signedMessage.length, theirPublicKey))
                return null;

            // message
            byte [] msg = new byte[signedMessage.length-signatureLength];
            for (int i = 0; i < msg.length; i ++)
                msg[i] = signedMessage[i+signatureLength];

            return msg;
        }

        /*
         * @description
         *   Signs the message using the secret key and returns a signature.
         * */
        public byte [] detached(byte [] message) {

            return null;
        }

        /*
         * @description
         *   Verifies the signature for the message and
         *   returns true if verification succeeded or false if it failed.
         * */
        public boolean detached_verify(byte [] message, byte [] signature) {

            return false;
        }

        /*
         * @description
         *   Generates new random key pair for signing and
         *   returns it as an object with publicKey and secretKey members
         * */
        public static class KeyPair {
            private byte [] publicKey;
            private byte [] secretKey;

            public KeyPair() {
                publicKey = new byte[publicKeyLength];
                secretKey = new byte[secretKeyLength];
            }

            public byte [] getPublicKey() {
                return publicKey;
            }

            public byte [] getSecretKey() {
                return secretKey;
            }
        }

        /*
         * @description
         *   Signs the message using the secret key and returns a signed message.
         * */
        public static KeyPair keyPair() {
            KeyPair kp = new KeyPair();

            crypto_sign_keypair(kp.getPublicKey(), kp.getSecretKey(), false);
            return kp;
        }

        public static KeyPair keyPair_fromSecretKey(byte [] secretKey) {
            KeyPair kp = new KeyPair();
            byte [] pk = kp.getPublicKey();
            byte [] sk = kp.getSecretKey();

            // copy sk
            for (int i = 0; i < kp.getSecretKey().length; i ++)
                sk[i] = secretKey[i];

            // copy pk from sk
            for (int i = 0; i < kp.getPublicKey().length; i ++)
                pk[i] = secretKey[32+i]; // hard-copy

            return kp;
        }

        public static KeyPair keyPair_fromSeed(byte [] seed) {
            KeyPair kp = new KeyPair();
            byte [] pk = kp.getPublicKey();
            byte [] sk = kp.getSecretKey();

            // copy sk
            for (int i = 0; i < seedLength; i ++)
                sk[i] = seed[i];

            // generate pk from sk
            crypto_sign_keypair(pk, sk, true);

            return kp;
        }

        /*
         * @description
         *   Length of signing public key in bytes.
         * */
        public static final int publicKeyLength = 32;

        /*
         * @description
         *   Length of signing secret key in bytes.
         * */
        public static final int secretKeyLength = 64;

        /*
         * @description
         *   Length of seed for nacl.sign.keyPair.fromSeed in bytes.
         * */
        public static final int seedLength      = 32;

        /*
         * @description
         *   Length of signature in bytes.
         * */
        public static final int signatureLength = 64;
    }


    ////////////////////////////////////////////////////////////////////////////////////
	/*
	 * @description
	 *   Codes below are ported from TweetNacl.c/TweetNacl.h
	 * */

    private static final byte [] _0 = new byte[16];
    private static final byte [] _9 = new byte[32];
    static {
        for (int i = 0; i < _0.length; i ++) _0[i] = 0;

        for (int i = 0; i < _9.length; i ++) _9[i] = 0; _9[0] = 9;
    }

    private static final long []     gf0 = new long[16];
    private static final long []     gf1 = new long[16];
    private static final long [] _121665 = new long[16];
    static {
        for (int i = 0; i < gf0.length; i ++) gf0[i] = 0;

        for (int i = 0; i < gf1.length; i ++) gf1[i] = 0; gf1[0] = 1;

        for (int i = 0; i < _121665.length; i ++) _121665[i] = 0; _121665[0] = 0xDB41; _121665[1] = 1;
    }

    private static final long []  D = new long [] {
            0x78a3, 0x1359, 0x4dca, 0x75eb,
            0xd8ab, 0x4141, 0x0a4d, 0x0070,
            0xe898, 0x7779, 0x4079, 0x8cc7,
            0xfe73, 0x2b6f, 0x6cee, 0x5203
    };
    private static final long [] D2 = new long [] {
            0xf159, 0x26b2, 0x9b94, 0xebd6,
            0xb156, 0x8283, 0x149a, 0x00e0,
            0xd130, 0xeef3, 0x80f2, 0x198e,
            0xfce7, 0x56df, 0xd9dc, 0x2406
    };
    private static final long []  X = new long [] {
            0xd51a, 0x8f25, 0x2d60, 0xc956,
            0xa7b2, 0x9525, 0xc760, 0x692c,
            0xdc5c, 0xfdd6, 0xe231, 0xc0a4,
            0x53fe, 0xcd6e, 0x36d3, 0x2169
    };
    private static final long []  Y = new long [] {
            0x6658, 0x6666, 0x6666, 0x6666,
            0x6666, 0x6666, 0x6666, 0x6666,
            0x6666, 0x6666, 0x6666, 0x6666,
            0x6666, 0x6666, 0x6666, 0x6666
    };
    private static final long []  I = new long [] {
            0xa0b0, 0x4a0e, 0x1b27, 0xc4ee,
            0xe478, 0xad2f, 0x1806, 0x2f43,
            0xd7a7, 0x3dfb, 0x0099, 0x2b4d,
            0xdf0b, 0x4fc1, 0x2480, 0x2b83
    };

    private static int L32(int x, int c)
    {
        return (x << c) | ((x&0xffffffff) >>> (32 - c));
    }

    private static int ld32(byte [] x, final int xoff, final int xlen)
    {
        int u =       (x[3+xoff]&0xff);
        u =    (u<<8)|(x[2+xoff]&0xff);
        u =    (u<<8)|(x[1+xoff]&0xff);
        return (u<<8)|(x[0+xoff]&0xff);
    }

    private static long dl64(byte [] x, final int xoff, final int xlen) {
        int i;
        long u=0;
        for (i = 0; i < 8; i ++) u=(u<<8)|(x[i+xoff]&0xff);
        return u;
    }

    private static void st32(byte [] x, final int xoff, final int xlen, int u)
    {
        int i;
        for (i = 0; i < 4; i ++) { x[i+xoff] = (byte)(u&0xff); u >>>= 8; }
    }

    private static void ts64(byte [] x, final int xoff, final int xlen, long u)
    {
        int i;
        for (i = 7;i >= 0;--i) { x[i+xoff] = (byte)(u&0xff); u >>>= 8; }
    }

    private static int vn(
            byte [] x, final int xoff, final int xlen,
            byte [] y, final int yoff, final int ylen,
            int n)
    {
        int i,d = 0;
        for (i = 0; i < n; i ++) d |= x[i+xoff]^y[i+yoff];
        return (1 & ((d - 1) >>> 8)) - 1;
    }

    private static int crypto_verify_16(
            byte [] x, final int xoff, final int xlen,
            byte [] y, final int yoff, final int ylen)
    {
        return vn(x,xoff,xlen,y,yoff,ylen,16);
    }
    public static int crypto_verify_16(byte [] x, byte [] y)
    {
        return crypto_verify_16(x, 0, x.length, y, 0, y.length);
    }

    private static int crypto_verify_32(
            byte [] x, final int xoff, final int xlen,
            byte [] y, final int yoff, final int ylen)
    {
        return vn(x,xoff,xlen,y,yoff,ylen,32);
    }
    public static int crypto_verify_32(byte [] x, byte [] y)
    {
        return crypto_verify_32(x, 0, x.length, y, 0, y.length);
    }

    private static void core(byte [] out, byte [] in, byte [] k, byte [] c, int h)
    {
        int [] w = new int[16], x = new int[16], y = new int[16], t = new int[4];
        int i,j,m;

        for (i = 0; i < 4; i ++) {
            x[5*i]  = ld32(c,  4*i,    4);
            x[1+i]  = ld32(k,  4*i,    4);
            x[6+i]  = ld32(in, 4*i,    4);
            x[11+i] = ld32(k,  16+4*i, 4);
        }

        for (i = 0; i < 16; i ++) y[i] = x[i];

        for (i = 0; i < 20; i ++) {
            for (j = 0; j < 4; j ++) {
                for (m = 0; m < 4; m ++) t[m] = x[(5*j+4*m)%16];
                t[1] ^= L32(t[0]+t[3], 7);
                t[2] ^= L32(t[1]+t[0], 9);
                t[3] ^= L32(t[2]+t[1],13);
                t[0] ^= L32(t[3]+t[2],18);
                for (m = 0; m < 4; m ++) w[4*j+(j+m)%4] = t[m];
            }
            for (m = 0; m < 16; m ++) x[m] = w[m];
        }

        if (h != 0) {
            for (i = 0; i < 16; i ++) x[i] += y[i];
            for (i = 0; i < 4; i ++) {
                x[5*i] -= ld32(c, 4*i, 4);
                x[6+i] -= ld32(in, 4*i, 4);
            }
            for (i = 0; i < 4; i ++) {
                st32(out, 4*i, 4, x[5*i]);
                st32(out, 16+4*i, 4, x[6+i]);
            }
        } else
            for (i = 0; i < 16; i ++) st32(out, 4*i, 4, x[i] + y[i]);

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < out.length; dbg ++) dbgt += " "+out[dbg];
        ///L/og.d(TAG, "core -> "+dbgt);
    }

    public static int crypto_core_salsa20(byte [] out, byte [] in, byte [] k, byte [] c)
    {
        core(out,in,k,c,0);

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < out.length; dbg ++) dbgt += " "+out[dbg];
        ///L/og.d(TAG, "crypto_core_salsa20 -> "+dbgt);

        return 0;
    }

    public static int crypto_core_hsalsa20(byte [] out, byte [] in, byte [] k, byte [] c)
    {
        core(out,in,k,c,1);

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < out.length; dbg ++) dbgt += " "+out[dbg];
        ///L/og.d(TAG, "crypto_core_hsalsa20 -> "+dbgt);

        return 0;
    }

    private static final byte[] sigma = { 101, 120, 112, 97, 110, 100, 32, 51, 50, 45, 98, 121, 116, 101, 32, 107 };
	/*static {
		try {
			sigma = "expand 32-byte k".getBytes("utf-8");
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}*/

    private static int crypto_stream_salsa20_xor(byte [] c, byte [] m, long b, byte [] n,final int noff,final int nlen, byte [] k)
    {
        byte[] z = new byte[16], x = new byte[64];
        int u,i;
        if (0==b) return 0;

        for (i = 0; i < 16; i ++) z[i] = 0;
        for (i = 0; i < 8; i ++) z[i] = n[i+noff];

        int coffset = 0;
        int moffset = 0;
        while (b >= 64) {
            crypto_core_salsa20(x,z,k,sigma);
            for (i = 0; i < 64; i ++) c[i+coffset] = (byte) (((m!=null?m[i+moffset]:0) ^ x[i]) & 0xff);
            u = 1;
            for (i = 8;i < 16;++i) {
                u += (int) (z[i]&0xff);
                z[i] = (byte) (u&0xff);
                u >>>= 8;
            }
            b -= 64;
            coffset += 64;
            if (m!=null) moffset += 64;
        }
        if (b!=0) {
            crypto_core_salsa20(x,z,k,sigma);
            for (i = 0; i < b; i ++) c[i+coffset] = (byte) (((m!=null?m[i+moffset]:0) ^ x[i]) & 0xff);
        }
        return 0;
    }
    public static int crypto_stream_salsa20_xor(byte [] c, byte [] m, long b, byte [] n, byte [] k) {
        return crypto_stream_salsa20_xor(c, m, b, n,0,n.length, k);
    }

    private static int crypto_stream_salsa20(byte [] c, long d, byte [] n,final int noff,final int nlen, byte [] k)
    {
        return crypto_stream_salsa20_xor(c,null,d, n,noff,nlen, k);
    }
    public static int crypto_stream_salsa20(byte [] c, long d, byte [] n, byte [] k) {
        return crypto_stream_salsa20(c, d, n,0,n.length, k);
    }

    public static int crypto_stream(byte [] c, long d, byte [] n, byte [] k)
    {
        byte[] s = new byte[32];
        crypto_core_hsalsa20(s,n,k,sigma);
        return crypto_stream_salsa20(c,d, n,16,n.length-16, s);
    }

    public static int crypto_stream_xor(byte []c,byte []m,long d,byte []n,byte []k)
    {
        byte[] s = new byte[32];
        crypto_core_hsalsa20(s,n,k,sigma);
        return crypto_stream_salsa20_xor(c,m,d, n,16,n.length-16, s);
    }

    private static void add1305(int [] h,int [] c)
    {
        int j,u = 0;
        for (j = 0; j < 17; j ++) {
            u += h[j] + c[j];
            h[j] = u & 255;
            u >>>= 8;
        }
    }

    private final static int minusp[] = { 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 252 };

    private static int crypto_onetimeauth(
            byte[] out,final int outoff,final int outlen,
            byte[] m,final int moff,final int mlen,
            long n,
            byte [] k)
    {
        int s,i,j,u;
        int [] x = new int[17], r = new int [17],
                h = new int[17], c = new int [17], g = new int[17];

        for (j = 0; j < 17; j ++) r[j] = h[j] = 0;

        for (j = 0; j < 16; j ++) r[j] = k[j];

        r[3]&=15;
        r[4]&=252;
        r[7]&=15;
        r[8]&=252;
        r[11]&=15;
        r[12]&=252;
        r[15]&=15;

        int moffset = moff;
        while (n > 0) {
            for (j = 0; j < 17; j ++) c[j] = 0;
            for (j = 0;(j < 16) && (j < n);++j) c[j] = m[j+moffset];
            c[j] = 1;

            moffset += j;

            n -= j;
            add1305(h,c);
            for (i = 0; i < 17; i ++) {
                x[i] = 0;
                for (j = 0; j < 17; j ++) x[i] += h[j] * ((j <= i) ? r[i - j] : 320 * r[i + 17 - j]);
            }
            for (i = 0; i < 17; i ++) h[i] = x[i];
            u = 0;
            for (j = 0; j < 16; j ++) {
                u += h[j];
                h[j] = u & 255;
                u >>>= 8;
            }
            u += h[16]; h[16] = u & 3;
            u = 5 * (u >>> 2);
            for (j = 0; j < 16; j ++) {
                u += h[j];
                h[j] = u & 255;
                u >>>= 8;
            }
            u += h[16]; h[16] = u;
        }

        for (j = 0; j < 17; j ++) g[j] = h[j];
        add1305(h,minusp);
        s = -(h[16] >>> 7);
        for (j = 0; j < 17; j ++) h[j] ^= s & (g[j] ^ h[j]);

        for (j = 0; j < 16; j ++) c[j] = k[j + 16];
        c[16] = 0;
        add1305(h,c);
        for (j = 0; j < 16; j ++) out[j+outoff] = (byte) (h[j]&0xff);

        return 0;
    }
    public static int crypto_onetimeauth(byte [] out, byte [] m, long n , byte [] k) {
        return crypto_onetimeauth(out,0,out.length, m,0,m.length, n, k);
    }

    private static int crypto_onetimeauth_verify(
            byte[] h,final int hoff,final int hlen,
            byte[] m,final int moff,final int mlen,
            long n,
            byte [] k)
    {
        byte[] x = new byte[16];
        crypto_onetimeauth(x,0,x.length, m,moff,mlen, n,k);
        return crypto_verify_16(h,hoff,hlen, x,0,x.length);
    }
    public static int crypto_onetimeauth_verify(byte [] h, byte [] m, long n, byte [] k) {
        return crypto_onetimeauth_verify(h,0,h.length, m,0,m.length, n, k);
    }
    public static int crypto_onetimeauth_verify(byte [] h, byte [] m, byte [] k) {
        return crypto_onetimeauth_verify(h, m, m!=null? m.length:0, k);
    }

    public static int crypto_secretbox(byte [] c, byte [] m, long d, byte [] n, byte [] k)
    {
        int i;

        if (d < 32) return -1;

        crypto_stream_xor(c,m,d,n,k);
        crypto_onetimeauth(c,16,c.length-16, c,32,c.length-32, d - 32, c);

        ///for (i = 0; i < 16; i ++) c[i] = 0;

        return 0;
    }

    public static int crypto_secretbox_open(byte []m,byte []c,long d,byte []n,byte []k)
    {
        int i;
        byte[] x = new byte[32];

        if (d < 32) return -1;

        crypto_stream(x,32,n,k);
        if (crypto_onetimeauth_verify(c,16,16, c,32,c.length-32, d-32, x) != 0) return -1;
        crypto_stream_xor(m,c,d,n,k);

        ///for (i = 0; i < 32; i ++) m[i] = 0;

        return 0;
    }

    private static void set25519(long [] r, long [] a)
    {
        int i;
        for (i = 0; i < 16; i ++) r[i]=a[i];
    }

    private static void car25519(long [] o,final int ooff,final int olen)
    {
        int i;
        long c;

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < o.length; dbg ++) dbgt += " "+o.get(dbg);
        ///L/og.d(TAG, "car25519 pre -> "+dbgt);

        for (i = 0; i < 16; i ++) {
            o[i+ooff] += (1L<<16);

            c = o[i+ooff]>>16;

            o[(i+1)*((i<15) ? 1 : 0)+ooff] += c-1+37*(c-1)*((i==15) ? 1 : 0);

            o[i+ooff] -= (c<<16);
        }

        ///dbgt = "";
        ///for (int dbg = 0; dbg < o.length; dbg ++) dbgt += " "+o.get(dbg);
        ///L/og.d(TAG, "car25519 -> "+dbgt);

    }

    private static void sel25519(
            long[] p,final int poff,final int plen,
            long[] q,final int qoff,final int qlen,
            int b)
    {
        int i;
        long t,c=~(b-1);

        for (i = 0; i < 16; i ++) {
            t = c & (p[i+poff] ^ q[i+qoff]);
            p[i+poff] ^= t;
            q[i+qoff] ^= t;
        }

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < p.length; dbg ++) dbgt += " "+p.get(dbg);
        ///L/og.d(TAG, "sel25519 -> "+dbgt);

    }

    private static void pack25519(byte [] o, long [] n,final int noff,final int nlen)
    {
        int i,j,b;
        long [] m = new long[16], t = new long[16];

        for (i = 0; i < 16; i ++) t[i] = n[i+noff];

        car25519(t,0,t.length);
        car25519(t,0,t.length);
        car25519(t,0,t.length);

        for (j = 0; j < 2; j ++) {
            m[0]=t[0]-0xffed;

            for(i=1;i<15;i++) {
                m[i]=t[i]-0xffff-((m[i-1] >> 16)&1);
                m[i-1]&=0xffff;
            }

            m[15]=t[15]-0x7fff-((m[14] >> 16)&1);
            b=(int) ((m[15] >> 16)&1);
            m[14]&=0xffff;

            sel25519(t,0,t.length, m,0,m.length, 1-b);
        }

        for (i = 0; i < 16; i ++) {
            o[2*i]=(byte) (t[i]&0xff);
            o[2*i+1]=(byte) (t[i] >> 8);
        }

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < o.length; dbg ++) dbgt += " "+o[dbg];
        ///L/og.d(TAG, "pack25519 -> "+dbgt);
    }

    private static int neq25519(long [] a, long [] b)
    {
        byte[] c = new byte[32], d = new byte[32];

        pack25519(c, a,0,a.length);
        pack25519(d, b,0,b.length);

        return crypto_verify_32(c,0,c.length, d,0,d.length);
    }

    private static byte par25519(long [] a)
    {
        byte[] d = new byte[32];

        pack25519(d, a,0,a.length);

        return (byte) (d[0]&1);
    }

    private static void unpack25519(long [] o, byte [] n)
    {
        int i;

        for (i = 0; i < 16; i ++) o[i]=(n[2*i]&0xff)+((long)((n[2*i+1]<<8)&0xffff));

        o[15]&=0x7fff;

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < o.length; dbg ++) dbgt += " "+o[dbg];
        ///L/og.d(TAG, "unpack25519 -> "+dbgt);
    }

    private static void A(
            long [] o,final int ooff,final int olen,
            long [] a,final int aoff,final int alen,
            long [] b,final int boff,final int blen)
    {
        int i;
        for (i = 0; i < 16; i ++) o[i+ooff] = a[i+aoff] + b[i+boff];
    }

    private static void Z(
            long [] o,final int ooff,final int olen,
            long [] a,final int aoff,final int alen,
            long [] b,final int boff,final int blen)
    {
        int i;
        for (i = 0; i < 16; i ++) o[i+ooff] = a[i+aoff] - b[i+boff];
    }

    private static void M(
            long [] o,final int ooff,final int olen,
            long [] a,final int aoff,final int alen,
            long [] b,final int boff,final int blen)
    {
        int i,j;
        long [] t = new long[31];

        for (i = 0; i < 31; i ++) t[i]=0;

        for (i = 0; i < 16; i ++) for (j = 0; j < 16; j ++) t[i+j]+=a[i+aoff]*b[j+boff];

        for (i = 0; i < 15; i ++) t[i]+=38*t[i+16];

        for (i = 0; i < 16; i ++) o[i+ooff]=t[i];

        car25519(o,ooff,olen);
        car25519(o,ooff,olen);

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < o.length; dbg ++) dbgt += " "+o.get(dbg);
        ///L/og.d(TAG, "M -> "+dbgt);
    }

    private static void S(
            long [] o,final int ooff,final int olen,
            long [] a,final int aoff,final int alen)
    {
        M(o,ooff,olen, a,aoff,alen, a,aoff,alen);
    }

    private static void inv25519(
            long [] o,final int ooff,final int olen,
            long [] i,final int ioff,final int ilen)
    {
        long [] c = new long[16];
        int a;

        for (a = 0; a < 16; a ++) c[a]=i[a+ioff];

        for(a=253;a>=0;a--) {
            S(c,0,c.length, c,0,c.length);
            if(a!=2&&a!=4) M(c,0,c.length, c,0,c.length, i,ioff,ilen);
        }

        for (a = 0; a < 16; a ++) o[a+ooff] = c[a];

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < o.length; dbg ++) dbgt += " "+o.get(dbg);
        ///L/og.d(TAG, "inv25519 -> "+dbgt);
    }

    private static void pow2523(long [] o,long [] i)
    {
        long [] c = new long[16];
        int a;

        for (a = 0; a < 16; a ++) c[a]=i[a];

        for(a=250;a>=0;a--) {
            S(c,0,c.length, c,0,c.length);
            if(a!=1) M(c,0,c.length, c,0,c.length, i,0,i.length);
        }

        for (a = 0; a < 16; a ++) o[a]=c[a];
    }

    public static int crypto_scalarmult(byte []q,byte []n,byte []p)
    {
        byte[] z = new byte[32];
        long[] x = new long[80];
        int r,i;

        long [] a = new long[16], b = new long[16], c = new long[16],
                d = new long[16], e = new long[16], f = new long[16];

        for (i = 0; i < 31; i ++) z[i]=n[i];

        z[31]=(byte) (((n[31]&127)|64) & 0xff);
        z[0]&=248;

        unpack25519(x,p);

        for (i = 0; i < 16; i ++) {
            b[i]=x[i];
            d[i]=a[i]=c[i]=0;
        }
        a[0]=d[0]=1;

        for(i=254;i>=0;--i) {
            r=(z[i>>>3]>>>(i&7))&1;
            sel25519(a,0,a.length, b,0,b.length, r);
            sel25519(c,0,c.length, d,0,d.length, r);
            A(e,0,e.length, a,0,a.length, c,0,c.length);
            Z(a,0,a.length, a,0,a.length, c,0,c.length);
            A(c,0,c.length, b,0,b.length, d,0,d.length);
            Z(b,0,b.length, b,0,b.length, d,0,d.length);
            S(d,0,d.length, e,0,e.length);
            S(f,0,f.length, a,0,a.length);
            M(a,0,a.length, c,0,c.length, a,0,a.length);
            M(c,0,c.length, b,0,b.length, e,0,e.length);
            A(e,0,e.length, a,0,a.length, c,0,c.length);
            Z(a,0,a.length, a,0,a.length, c,0,c.length);
            S(b,0,b.length, a,0,a.length);
            Z(c,0,c.length, d,0,d.length, f,0,f.length);
            M(a,0,a.length, c,0,c.length, _121665,0,_121665.length);
            A(a,0,a.length, a,0,a.length, d,0,d.length);
            M(c,0,c.length, c,0,c.length, a,0,a.length);
            M(a,0,a.length, d,0,d.length, f,0,f.length);
            M(d,0,d.length, b,0,b.length, x,0,x.length);
            S(b,0,b.length, e,0,e.length);
            sel25519(a,0,a.length, b,0,b.length, r);
            sel25519(c,0,c.length, d,0,d.length, r);
        }

        for (i = 0; i < 16; i ++) {
            x[i+16]=a[i];
            x[i+32]=c[i];
            x[i+48]=b[i];
            x[i+64]=d[i];
        }

        inv25519(x, 32, x.length-32, x, 32, x.length-32);

        M(x,16,x.length-16, x,16,x.length-16, x,32,x.length-32);

        pack25519(q, x,16,x.length-16);

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < q.length; dbg ++) dbgt += " "+q[dbg];
        ///L/og.d(TAG, "crypto_scalarmult -> "+dbgt);

        return 0;
    }

    public static int crypto_scalarmult_base(byte []q,byte []n)
    {
        return crypto_scalarmult(q,n,_9);
    }

    public static int crypto_box_keypair(byte [] y, byte [] x)
    {
        randombytes(x,32);
        return crypto_scalarmult_base(y,x);
    }

    public static int crypto_box_beforenm(byte []k,byte []y,byte []x)
    {
        byte[] s = new byte[32];
        crypto_scalarmult(s,x,y);

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < s.length; dbg ++) dbgt += " "+s[dbg];
        ///L/og.d(TAG, "crypto_box_beforenm -> "+dbgt);

        return crypto_core_hsalsa20(k,_0,s,sigma);
    }

    public static int crypto_box_afternm(byte []c,byte []m,long d,byte []n,byte []k)
    {
        return crypto_secretbox(c,m,d,n,k);
    }

    public static int crypto_box_open_afternm(byte []m,byte []c,long d,byte []n,byte []k)
    {
        return crypto_secretbox_open(m,c,d,n,k);
    }

    public static int crypto_box(byte []c,byte []m,long d,byte []n,byte []y,byte []x)
    {
        byte[] k = new byte[32];

        ///L/og.d(TAG, "crypto_box start ...");

        crypto_box_beforenm(k,y,x);
        return crypto_box_afternm(c,m,d,n,k);
    }

    public static int crypto_box_open(byte []m,byte []c,long d,byte []n,byte []y,byte []x)
    {
        byte[] k = new byte[32];
        crypto_box_beforenm(k,y,x);
        return crypto_box_open_afternm(m,c,d,n,k);
    }

    private static long R(long x,int c)           { return (x >>> c) | (x << (64 - c)); }

    private static long Ch( long x,long y,long z) { return (x & y) ^ (~x & z);          }
    private static long Maj(long x,long y,long z) { return (x & y) ^ (x & z) ^ (y & z); }

    private static long Sigma0(long x) { return R(x,28) ^ R(x,34) ^ R(x,39); }
    private static long Sigma1(long x) { return R(x,14) ^ R(x,18) ^ R(x,41); }
    private static long sigma0(long x) { return R(x, 1) ^ R(x, 8) ^ (x >>> 7); }
    private static long sigma1(long x) { return R(x,19) ^ R(x,61) ^ (x >>> 6); }

    private static final long K[] = {
            0x428a2f98d728ae22L, 0x7137449123ef65cdL, 0xb5c0fbcfec4d3b2fL, 0xe9b5dba58189dbbcL,
            0x3956c25bf348b538L, 0x59f111f1b605d019L, 0x923f82a4af194f9bL, 0xab1c5ed5da6d8118L,
            0xd807aa98a3030242L, 0x12835b0145706fbeL, 0x243185be4ee4b28cL, 0x550c7dc3d5ffb4e2L,
            0x72be5d74f27b896fL, 0x80deb1fe3b1696b1L, 0x9bdc06a725c71235L, 0xc19bf174cf692694L,
            0xe49b69c19ef14ad2L, 0xefbe4786384f25e3L, 0x0fc19dc68b8cd5b5L, 0x240ca1cc77ac9c65L,
            0x2de92c6f592b0275L, 0x4a7484aa6ea6e483L, 0x5cb0a9dcbd41fbd4L, 0x76f988da831153b5L,
            0x983e5152ee66dfabL, 0xa831c66d2db43210L, 0xb00327c898fb213fL, 0xbf597fc7beef0ee4L,
            0xc6e00bf33da88fc2L, 0xd5a79147930aa725L, 0x06ca6351e003826fL, 0x142929670a0e6e70L,
            0x27b70a8546d22ffcL, 0x2e1b21385c26c926L, 0x4d2c6dfc5ac42aedL, 0x53380d139d95b3dfL,
            0x650a73548baf63deL, 0x766a0abb3c77b2a8L, 0x81c2c92e47edaee6L, 0x92722c851482353bL,
            0xa2bfe8a14cf10364L, 0xa81a664bbc423001L, 0xc24b8b70d0f89791L, 0xc76c51a30654be30L,
            0xd192e819d6ef5218L, 0xd69906245565a910L, 0xf40e35855771202aL, 0x106aa07032bbd1b8L,
            0x19a4c116b8d2d0c8L, 0x1e376c085141ab53L, 0x2748774cdf8eeb99L, 0x34b0bcb5e19b48a8L,
            0x391c0cb3c5c95a63L, 0x4ed8aa4ae3418acbL, 0x5b9cca4f7763e373L, 0x682e6ff3d6b2b8a3L,
            0x748f82ee5defb2fcL, 0x78a5636f43172f60L, 0x84c87814a1f0ab72L, 0x8cc702081a6439ecL,
            0x90befffa23631e28L, 0xa4506cebde82bde9L, 0xbef9a3f7b2c67915L, 0xc67178f2e372532bL,
            0xca273eceea26619cL, 0xd186b8c721c0c207L, 0xeada7dd6cde0eb1eL, 0xf57d4f7fee6ed178L,
            0x06f067aa72176fbaL, 0x0a637dc5a2c898a6L, 0x113f9804bef90daeL, 0x1b710b35131c471bL,
            0x28db77f523047d84L, 0x32caab7b40c72493L, 0x3c9ebe0a15c9bebcL, 0x431d67c49c100d4cL,
            0x4cc5d4becb3e42b6L, 0x597f299cfc657e2aL, 0x5fcb6fab3ad6faecL, 0x6c44198c4a475817L
    };

    // TBD... long length n
    ///int crypto_hashblocks(byte [] x, byte [] m, long n)
    private static int crypto_hashblocks(byte [] x, byte [] m,final int moff,final int mlen, int n)
    {
        long [] z = new long [8], b = new long [8], a = new long [8], w = new long [16];
        long t;
        int i,j;

        for (i = 0; i < 8; i ++) z[i] = a[i] = dl64(x, 8*i, x.length-8*i);

        int moffset = moff;

        while (n >= 128) {
            for (i = 0; i < 16; i ++) w[i] = dl64(m, 8*i+moffset, mlen-8*i);

            for (i = 0; i < 80; i ++) {
                for (j = 0; j < 8; j ++) b[j] = a[j];

                t = a[7] + Sigma1(a[4]) + Ch(a[4],a[5],a[6]) + K[i] + w[i%16];
                b[7] = t + Sigma0(a[0]) + Maj(a[0],a[1],a[2]);
                b[3] += t;

                for (j = 0; j < 8; j ++) a[(j+1)%8] = b[j];

                if (i%16 == 15)
                    for (j = 0; j < 16; j ++)
                        w[j] += w[(j+9)%16] + sigma0(w[(j+1)%16]) + sigma1(w[(j+14)%16]);
            }

            for (i = 0; i < 8; i ++) { a[i] += z[i]; z[i] = a[i]; }

            moffset += 128;
            n -= 128;
        }

        for (i = 0; i < 8; i ++) ts64(x,8*i,x.length-8*i, z[i]);

        return n;
    }
    public static int crypto_hashblocks(byte [] x, byte [] m, int n) {
        return crypto_hashblocks(x, m,0,m.length, n);
    }

    private final static byte iv[] = {
            0x6a,0x09,(byte) 0xe6,0x67,(byte) 0xf3,(byte) 0xbc,(byte) 0xc9,0x08,
            (byte) 0xbb,0x67,(byte) 0xae,(byte) 0x85,(byte) 0x84,(byte) 0xca,(byte) 0xa7,0x3b,
            0x3c,0x6e,(byte) 0xf3,0x72,(byte) 0xfe,(byte) 0x94,(byte) 0xf8,0x2b,
            (byte) 0xa5,0x4f,(byte) 0xf5,0x3a,0x5f,0x1d,0x36,(byte) 0xf1,
            0x51,0x0e,0x52,0x7f,(byte) 0xad,(byte) 0xe6,(byte) 0x82,(byte) 0xd1,
            (byte) 0x9b,0x05,0x68,(byte) 0x8c,0x2b,0x3e,0x6c,0x1f,
            0x1f,(byte) 0x83,(byte) 0xd9,(byte) 0xab,(byte) 0xfb,0x41,(byte) 0xbd,0x6b,
            0x5b,(byte) 0xe0,(byte) 0xcd,0x19,0x13,0x7e,0x21,0x79
    } ;

    // TBD 64bits of n
    ///int crypto_hash(byte [] out, byte [] m, long n)
    private static int crypto_hash(byte [] out, byte [] m,final int moff,final int mlen, int n)
    {
        byte[] h = new byte[64], x = new byte [256];
        long b = n;
        int i;

        for (i = 0; i < 64; i ++) h[i] = iv[i];

        crypto_hashblocks(h, m,moff,mlen, n);
        ///m += n;
        n &= 127;
        ///m -= n;

        for (i = 0; i < 256; i ++) x[i] = 0;

        for (i = 0; i < n; i ++) x[i] = m[i+moff];
        x[n] = (byte) 128;

        n = 256-128*(n<112?1:0);
        x[n-9] = (byte) (b >>> 61);
        ts64(x,n-8,x.length-(n-8), b<<3);
        crypto_hashblocks(h, x,0,x.length, n);

        for (i = 0; i < 64; i ++) out[i] = h[i];

        return 0;
    }
    public static int crypto_hash(byte [] out, byte [] m, int n) {
        return crypto_hash(out, m,0,m.length, n);
    }
    public static int crypto_hash(byte [] out, byte [] m) {
        return crypto_hash(out, m, m!=null? m.length : 0);
    }

    // gf: long[16]
    ///private static void add(gf p[4],gf q[4])
    private static void add(long [] p[], long [] q[])
    {
        long [] a = new long[16];
        long [] b = new long[16];
        long [] c = new long[16];
        long [] d = new long[16];
        long [] t = new long[16];
        long [] e = new long[16];
        long [] f = new long[16];
        long [] g = new long[16];
        long [] h = new long[16];


        long [] p0 = p[0];
        long [] p1 = p[1];
        long [] p2 = p[2];
        long [] p3 = p[3];

        long [] q0 = q[0];
        long [] q1 = q[1];
        long [] q2 = q[2];
        long [] q3 = q[3];

        Z(a,0,a.length, p1,0,p1.length, p0,0,p0.length);
        Z(t,0,t.length, q1,0,q1.length, q0,0,q0.length);
        M(a,0,a.length, a,0,a.length,   t,0,t.length);
        A(b,0,b.length, p0,0,p0.length, p1,0,p1.length);
        A(t,0,t.length, q0,0,q0.length, q1,0,q1.length);
        M(b,0,b.length, b,0,b.length,   t,0,t.length);
        M(c,0,c.length, p3,0,p3.length, q3,0,q3.length);
        M(c,0,c.length, c,0,c.length,   D2,0,D2.length);
        M(d,0,d.length, p2,0,p2.length, q2,0,q2.length);

        A(d,0,d.length, d,0,d.length, d,0,d.length);
        Z(e,0,e.length, b,0,b.length, a,0,a.length);
        Z(f,0,f.length, d,0,d.length, c,0,c.length);
        A(g,0,g.length, d,0,d.length, c,0,c.length);
        A(h,0,h.length, b,0,b.length, a,0,a.length);

        M(p0,0,p0.length, e,0,e.length, f,0,f.length);
        M(p1,0,p1.length, h,0,h.length, g,0,g.length);
        M(p2,0,p2.length, g,0,g.length, f,0,f.length);
        M(p3,0,p3.length, e,0,e.length, h,0,h.length);
    }

    private static void cswap(long [] p[], long [] q[], byte b)
    {
        int i;

        for (i = 0; i < 4; i ++)
            sel25519(p[i],0,p[i].length, q[i],0,q[i].length, b);
    }

    private static void pack(byte [] r, long [] p[])
    {
        long [] tx = new long[16];
        long [] ty = new long[16];
        long [] zi = new long[16];

        inv25519(zi,0,zi.length, p[2],0,p[2].length);

        M(tx,0,tx.length, p[0],0,p[0].length, zi,0,zi.length);
        M(ty,0,ty.length, p[1],0,p[1].length, zi,0,zi.length);

        pack25519(r, ty,0,ty.length);

        r[31] ^= par25519(tx) << 7;
    }

    private static void scalarmult(long [] p[], long [] q[], byte[] s,final int soff,final int slen)
    {
        int i;

        set25519(p[0],gf0);
        set25519(p[1],gf1);
        set25519(p[2],gf1);
        set25519(p[3],gf0);

        for (i = 255;i >= 0;--i) {
            byte b = (byte) ((s[i/8+soff] >> (i&7))&1);

            cswap(p,q,b);
            add(q,p);
            add(p,p);
            cswap(p,q,b);
        }

        ///String dbgt = "";
        ///for (int dbg = 0; dbg < p.length; dbg ++) for (int dd = 0; dd < p[dbg].length; dd ++) dbgt += " "+p[dbg][dd];
        ///L/og.d(TAG, "scalarmult -> "+dbgt);
    }

    private static void scalarbase(long [] p[], byte[] s,final int soff,final int slen)
    {
        long [] [] q = new long [4] [];

        q[0] = new long [16];
        q[1] = new long [16];
        q[2] = new long [16];
        q[3] = new long [16];

        set25519(q[0],X);
        set25519(q[1],Y);
        set25519(q[2],gf1);
        M(q[3],0,q[3].length, X,0,X.length, Y,0,Y.length);
        scalarmult(p,q, s,soff,slen);
    }

    public static int  crypto_sign_keypair(byte [] pk, byte [] sk, boolean seeded) {
        byte [] d = new byte[64];
        long [] [] p = new long [4] [];

        p[0] = new long [16];
        p[1] = new long [16];
        p[2] = new long [16];
        p[3] = new long [16];

        int i;

        if (!seeded) randombytes(sk, 32);
        crypto_hash(d, sk,0,sk.length, 32);
        d[0] &= 248;
        d[31] &= 127;
        d[31] |= 64;

        scalarbase(p, d,0,d.length);
        pack(pk, p);

        for (i = 0; i < 32; i++) sk[i+32] = pk[i];
        return 0;
    }

    private static final long L[] = {
            0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,
            0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
            0,    0,    0,    0,    0,    0,    0,    0,
            0,    0,    0,    0,    0,    0,    0,    0x10
    };

    private static void modL(byte[] r,final int roff,final int rlen, long x[])
    {
        long carry;
        int i, j;

        for (i = 63;i >= 32;--i) {
            carry = 0;
            for (j = i - 32;j < i - 12;++j) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)];
                carry = (x[j] + 128) >> 8;
                x[j] -= carry << 8;
            }
            x[j] += carry;
            x[i] = 0;
        }
        carry = 0;

        for (j = 0; j < 32; j ++) {
            x[j] += carry - (x[31] >> 4) * L[j];
            carry = x[j] >> 8;
            x[j] &= 255;
        }

        for (j = 0; j < 32; j ++) x[j] -= carry * L[j];

        for (i = 0; i < 32; i ++) {
            x[i+1] += x[i] >> 8;
            r[i+roff] = (byte) (x[i] & 255);
        }
    }

    private static void reduce(byte [] r)
    {
        long[] x = new long [64];
        int i;

        for (i = 0; i < 64; i ++) x[i] = (long) (r[i]&0xff);

        for (i = 0; i < 64; i ++) r[i] = 0;

        modL(r,0,r.length, x);
    }

    // TBD... 64bits of n
    ///int crypto_sign(byte [] sm, long * smlen, byte [] m, long n, byte [] sk)
    public static int crypto_sign(byte [] sm, long dummy /* *smlen not used*/, byte [] m, int/*long*/ n, byte [] sk)
    {
        byte[] d = new byte[64], h = new byte[64], r = new byte[64];

        int i, j;
        long [] x = new long[64];

        long [] [] p = new long [4] [];
        p[0] = new long [16];
        p[1] = new long [16];
        p[2] = new long [16];
        p[3] = new long [16];

        crypto_hash(d, sk,0,sk.length, 32);
        d[0] &= 248;
        d[31] &= 127;
        d[31] |= 64;

        ///*smlen = n+64;

        for (i = 0; i < n; i ++) sm[64 + i] = m[i];

        for (i = 0; i < 32; i ++) sm[32 + i] = d[32 + i];

        crypto_hash(r, sm,32,sm.length-32, n+32);
        reduce(r);
        scalarbase(p, r,0,r.length);
        pack(sm,p);

        for (i = 0; i < 32; i ++) sm[i+32] = sk[i+32];
        crypto_hash(h, sm,0,sm.length, n + 64);
        reduce(h);

        for (i = 0; i < 64; i ++) x[i] = 0;

        for (i = 0; i < 32; i ++) x[i] = (long) (r[i]&0xff);

        for (i = 0; i < 32; i ++) for (j = 0; j < 32; j ++) x[i+j] += (h[i]&0xff) * (long) (d[j]&0xff);

        modL(sm,32,sm.length-32, x);

        return 0;
    }

    private static int unpackneg(long [] r[], byte p[])
    {
        long []    t = new long [16];
        long []  chk = new long [16];
        long []  num = new long [16];
        long []  den = new long [16];
        long [] den2 = new long [16];
        long [] den4 = new long [16];
        long [] den6 = new long [16];

        set25519(r[2],gf1);
        unpack25519(r[1], p);
        S(num,0,num.length, r[1],0,r[1].length);
        M(den,0,den.length, num,0,num.length, D,0,D.length);
        Z(num,0,num.length, num,0,num.length, r[2],0,r[2].length);
        A(den,0,den.length, r[2],0,r[2].length, den,0,den.length);

        S(den2,0,den2.length, den,0,den.length);
        S(den4,0,den4.length, den2,0,den2.length);
        M(den6,0,den6.length, den4,0,den4.length, den2,0,den2.length);
        M(t,0,t.length, den6,0,den6.length, num,0,num.length);
        M(t,0,t.length, t,0,t.length, den,0,den.length);

        pow2523(t, t);
        M(t,0,t.length, t,0,t.length, num,0,num.length);
        M(t,0,t.length, t,0,t.length, den,0,den.length);
        M(t,0,t.length, t,0,t.length, den,0,den.length);
        M(r[0],0,r[0].length, t,0,t.length, den,0,den.length);

        S(chk,0,chk.length, r[0],0,r[0].length);
        M(chk,0,chk.length, chk,0,chk.length, den,0,den.length);
        if (neq25519(chk, num)!=0) M(r[0],0,r[0].length, r[0],0,r[0].length, I,0,I.length);

        S(chk,0,chk.length, r[0],0,r[0].length);
        M(chk,0,chk.length, chk,0,chk.length, den,0,den.length);
        if (neq25519(chk, num)!=0) return -1;

        if (par25519(r[0]) == (p[31]>>7)) Z(r[0],0,r[0].length, gf0,0,gf0.length, r[0],0,r[0].length);

        M(r[3],0,r[3].length, r[0],0,r[0].length, r[1],0,r[1].length);
        return 0;
    }

    /// TBD 64bits of mlen
    ///int crypto_sign_open(byte []m,long *mlen,byte []sm,long n,byte []pk)
    public static int crypto_sign_open(byte [] m, long dummy /* *mlen not used*/, byte [] sm, int/*long*/ n, byte []pk)
    {
        int i;
        byte[] t = new byte[32], h = new byte[64];

        long [] [] p = new long [4] [];
        p[0] = new long [16];
        p[1] = new long [16];
        p[2] = new long [16];
        p[3] = new long [16];

        long [] [] q = new long [4] [];
        q[0] = new long [16];
        q[1] = new long [16];
        q[2] = new long [16];
        q[3] = new long [16];

        ///*mlen = -1;

        if (n < 64) return -1;

        if (unpackneg(q,pk)!=0) return -1;

        for (i = 0; i < n; i ++) m[i] = sm[i];

        for (i = 0; i < 32; i ++) m[i+32] = pk[i];

        crypto_hash(h, m,0,m.length, n);

        reduce(h);
        scalarmult(p,q, h,0,h.length);

        scalarbase(q, sm,32,sm.length-32);
        add(p,q);
        pack(t,p);

        n -= 64;
        if (crypto_verify_32(sm,0,sm.length, t,0,t.length)!=0) {
            // optimizing it
            ///for (i = 0; i < n; i ++) m[i] = 0;
            return -1;
        }

        // TBD optimizing ...
        ///for (i = 0; i < n; i ++) m[i] = sm[i + 64];
        ///*mlen = n;

        return 0;
    }

    /*
     * @description
     *   Java Random generator
     * */
    private static final Random jrandom = new Random();

    private static void randombytes(byte [] x, int len) {
        int ret = len % 8;
        long rnd;

        for (int i = 0; i < len-ret; i += 8) {
            rnd = jrandom.nextLong();

            x[i+0] = (byte) (rnd >>>  0);
            x[i+1] = (byte) (rnd >>>  8);
            x[i+2] = (byte) (rnd >>> 16);
            x[i+3] = (byte) (rnd >>> 24);
            x[i+4] = (byte) (rnd >>> 32);
            x[i+5] = (byte) (rnd >>> 40);
            x[i+6] = (byte) (rnd >>> 48);
            x[i+7] = (byte) (rnd >>> 56);
        }

        if (ret > 0) {
            rnd = jrandom.nextLong();
            for (int i = len-ret; i < len; i ++)
                x[i] = (byte) (rnd >>> 8*i);
        }
    }

}