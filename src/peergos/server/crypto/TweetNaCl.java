package peergos.server.crypto;

import peergos.shared.crypto.*;
import peergos.shared.util.*;

import java.security.*;
import java.util.Arrays;
import java.util.Random;

/* Ported from the original C by Ian Preston and Chris Boddy
 * crypto_hash() is ported from TweetNaCl.js
 * Released under GPL 2
 */
public class TweetNaCl {

    public static final int crypto_auth_hmacsha512256_tweet_BYTES = 32;
    public static final int crypto_auth_hmacsha512256_tweet_KEYBYTES = 32;
    public static final int BOX_PUBLIC_KEY_BYTES = 32;
    public static final int BOX_SECRET_KEY_BYTES = 32;
    public static final int BOX_SHARED_KEY_BYTES = 32;
    public static final int BOX_NONCE_BYTES = 24;
    public static final int BOX_OVERHEAD_BYTES = 16;
    public static final int SIGNATURE_SIZE_BYTES = 64;
    public static final int SIGN_PUBLIC_KEY_BYTES = 32;
    public static final int SIGN_SECRET_KEY_BYTES = 64;
    public static final int SIGN_KEYPAIR_SEED_BYTES = 32;
    public static final int SECRETBOX_KEY_BYTES = 32;
    public static final int SECRETBOX_NONCE_BYTES = 24;
    public static final int SECRETBOX_OVERHEAD_BYTES = 16;
    public static final int HASH_SIZE_BYTES = 64; // SHA-512
    private static final int SECRETBOX_INTERNAL_OVERHEAD_BYTES = 32;

    public static class InvalidSignatureException extends RuntimeException {}

    public static void crypto_sign_keypair(byte[] pk, byte[] sk, boolean isSeeded)
    {
        byte[] d = new byte[64];
        long[][] /*gf*/ p = new long[4][GF_LEN];
        int i;

        if (!isSeeded)
            randombytes(sk, 32);
        crypto_hash(d, sk, 32);
        d[0] &= 248;
        d[31] &= 127;
        d[31] |= 64;

        scalarbase(p,d, 0);
        pack(pk,p);

        for (i=0;i < 32;++i)sk[32 + i] = pk[i];
    }

    public static int crypto_box_keypair(byte[] y,byte[] x, boolean isSeeded)
    {
        if (!isSeeded)
            randombytes(x,32);
        return crypto_scalarmult_base(y,x);
    }

    public static int crypto_scalarmult_base(byte[] q,byte[] n)
    {
        return crypto_scalarmult(q, n, _9);
    }

    public static byte[] crypto_sign(byte[] message, byte[] secretSigningKey) {
        byte[] signedMessage = new byte[message.length + TweetNaCl.SIGNATURE_SIZE_BYTES];
        TweetNaCl.crypto_sign(signedMessage, message, message.length, secretSigningKey);
        return signedMessage;
    }

    public static byte[] crypto_sign_open(byte[] signed, byte[] publicSigningKey) {
        byte[] message = new byte[signed.length];
        int res = TweetNaCl.crypto_sign_open(message, signed, signed.length, publicSigningKey);
        if (res != 0)
            throw new InvalidSignatureException();
        return Arrays.copyOfRange(message, 64, message.length);
    }

    public static byte[] crypto_box(byte[] message, byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey) {
        if (nonce.length != BOX_NONCE_BYTES)
            throw new IllegalStateException("Illegal nonce length: "+nonce.length);
        byte[] cipherText = new byte[SECRETBOX_INTERNAL_OVERHEAD_BYTES + message.length];
        byte[] paddedMessage = new byte[SECRETBOX_INTERNAL_OVERHEAD_BYTES + message.length];
        System.arraycopy(message, 0, paddedMessage, SECRETBOX_INTERNAL_OVERHEAD_BYTES, message.length);
        TweetNaCl.crypto_box(cipherText, paddedMessage, paddedMessage.length, nonce, theirPublicBoxingKey, ourSecretBoxingKey);
        return Arrays.copyOfRange(cipherText, 16, cipherText.length);
    }

    public static byte[] crypto_box_open(byte[] cipher, byte[] nonce, byte[] theirPublicBoxingKey, byte[] secretBoxingKey) {
        byte[] paddedCipher = new byte[cipher.length + 16];
        System.arraycopy(cipher, 0, paddedCipher, 16, cipher.length);
        byte[] rawText = new byte[paddedCipher.length];
        int res = TweetNaCl.crypto_box_open(rawText, paddedCipher, paddedCipher.length, nonce, theirPublicBoxingKey, secretBoxingKey);
        if (res != 0)
            throw new InvalidCipherTextException();
        return Arrays.copyOfRange(rawText, 32, rawText.length);
    }

    public static byte[] secretbox(byte[] mesage, byte[] nonce, byte[] key) {
        byte[] m = new byte[SECRETBOX_INTERNAL_OVERHEAD_BYTES + mesage.length];
        byte[] c = new byte[m.length];
        System.arraycopy(mesage, 0, m, SECRETBOX_INTERNAL_OVERHEAD_BYTES, mesage.length);
        crypto_secretbox(c, m, m.length, nonce, key);
        return Arrays.copyOfRange(c, SECRETBOX_OVERHEAD_BYTES, c.length);
    }

    public static byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key) {
        byte[] c = new byte[SECRETBOX_OVERHEAD_BYTES + cipher.length];
        byte[] m = new byte[c.length];
        System.arraycopy(cipher, 0, c, SECRETBOX_OVERHEAD_BYTES, cipher.length);
        boolean validCipher = c.length >= 32;
        boolean success = crypto_secretbox_open(m, c, c.length, nonce, key) == 0;
        boolean isValid = validCipher && success;

        String exMsg = "Invalid encryption! ["+ cipher.length + "] = " +
                ArrayOps.bytesToHex(Arrays.copyOfRange(cipher, 0, Math.min(cipher.length, 64))) + " ... " +
                ArrayOps.bytesToHex(Arrays.copyOfRange(cipher, Math.max(0, cipher.length - 64), cipher.length));

        InvalidCipherTextException ex =  new InvalidCipherTextException(exMsg);

        if (! isValid)
            throw ex;

        return Arrays.copyOfRange(m, SECRETBOX_INTERNAL_OVERHEAD_BYTES, m.length);
    }

    private static byte[] _0 = new byte[16], _9 = new byte[32];
    static {
        _9[0] = 9;
    }
    private static final int GF_LEN = 16;
    private static long[]  gf0 = new long[GF_LEN];
    private static long[] gf1 = new long[GF_LEN]; static{gf1[0] = 1;}
    private static long[]  _121665 = new long[GF_LEN]; static{_121665[0] = 0xDB41; _121665[1] =1;}
    private static long[]  D = new long[]{0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203},
            D2 = new long[]{0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406},
            X = new long[]{0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169},
            Y = new long[]{0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666},
            I = new long[]{0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83};

    private static int L32(int x,int c) { return (x << c) | (x >>> (32 - c)); }

    public static int ld32(byte[] x, int off)
    {
        int u = x[off + 3] & 0xff;
        u = (u<<8)|(x[off + 2] & 0xff);
        u = (u<<8)|(x[off + 1] & 0xff);
        return (u<<8)|(x[off + 0] & 0xff);
    }

    private static void st32(byte[] x, int off, int u)
    {
        int i;
        for (i=0;i < 4;++i){ x[off + i] = (byte)u; u >>= 8; }
    }

    private static int vn(byte[] x, int xOff, byte[] y,int n)
    {
        int i,d = 0;
        for (i=0;i < n;++i)d |= 0xff & (x[xOff + i]^y[i]);
        return (1 & ((d - 1) >> 8)) - 1;
    }

    private static int crypto_verify_16(byte[] x, int xOff, byte[] y)
    {
        return vn(x, xOff, y, 16);
    }

    private static int crypto_verify_32(byte[] x,byte[] y)
    {
        return vn(x, 0, y,32);
    }

    private static void core(byte[] out,byte[] in,byte[] k,byte[] c,int h)
    {
        int[] w = new int[16],x = new int[16],y = new int[16],t = new int[4];
        int i,j,m;

        for (i=0;i < 4;++i){
            x[5*i] = ld32(c,4*i);
            x[1+i] = ld32(k,4*i);
            x[6+i] = ld32(in,4*i);
            x[11+i] = ld32(k,16+4*i);
        }

        for (i=0;i < 16;++i)y[i] = x[i];

        for (i=0;i < 20;++i){
            for (j=0;j < 4;++j){
                for (m=0;m < 4;++m)t[m] = x[(5*j+4*m)%16];
                t[1] ^= L32(t[0]+t[3], 7);
                t[2] ^= L32(t[1]+t[0], 9);
                t[3] ^= L32(t[2]+t[1],13);
                t[0] ^= L32(t[3]+t[2],18);
                for (m=0;m < 4;++m)w[4*j+(j+m)%4] = t[m];
            }
            for (m=0;m < 16;++m)x[m] = w[m];
        }

        if (h != 0) {
            for (i=0;i < 16;++i)x[i] += y[i];
            for (i=0;i < 4;++i){
                x[5*i] -= ld32(c,4*i);
                x[6+i] -= ld32(in,4*i);
            }
            for (i=0;i < 4;++i){
                st32(out, 4*i,x[5*i]);
                st32(out, 16+4*i,x[6+i]);
            }
        } else
            for (i=0;i < 16;++i)st32(out, 4 * i,x[i] + y[i]);
    }

    private static int crypto_core_salsa20(byte[] out,byte[] in,byte[] k,byte[] c)
    {
        core(out,in,k,c,0);
        return 0;
    }

    private static int crypto_core_hsalsa20(byte[] out,byte[] in,byte[] k,byte[] c)
    {
        core(out,in,k,c,1);
        return 0;
    }

    private static byte[] sigma = { 101, 120, 112, 97, 110, 100, 32, 51, 50, 45, 98, 121, 116, 101, 32, 107 };

    private static int crypto_stream_salsa20_xor(byte[] c,byte[] m,long b,byte[] n, int nOff, byte[] k)
    {
        byte[] z = new byte[16],x = new byte[64];
        int u,i;
        if (b == 0) return 0;
        for (i=0;i < 16;++i)z[i] = 0;
        for (i=0;i < 8;++i)z[i] = n[nOff + i];
        int cOff = 0;
        int mOff = 0;
        while (b >= 64) {
            crypto_core_salsa20(x,z,k,sigma);
            for (i=0;i < 64; ++i) c[cOff + i] = (byte)((m != null ? m[mOff + i]:0)^ x[i]);
            u = 1;
            for (i = 8;i < 16;++i) {
                u += 0xff & z[i];
                z[i] = (byte)u;
                u >>= 8;
            }
            b -= 64;
            cOff += 64;
            if (m != null) mOff += 64;
        }
        if (b != 0) {
            crypto_core_salsa20(x,z,k,sigma);
            for (i=0;i < b; i++) c[cOff + i] = (byte)((m != null ? m[mOff + i]:0)^ x[i]);
        }
        return 0;
    }

    private static int crypto_stream_salsa20(byte[] c,long d,byte[] n, int nOff, byte[] k)
    {
        return crypto_stream_salsa20_xor(c,null,d,n, nOff, k);
    }

    private static int crypto_stream(byte[] c,long d,byte[] n,byte[] k)
    {
        byte[] s = new byte[32];
        crypto_core_hsalsa20(s,n,k,sigma);
        return crypto_stream_salsa20(c, d, n, 16, s);
    }

    private static int crypto_stream_xor(byte[] c,byte[] m,long d,byte[] n,byte[] k)
    {
        byte[] s = new byte[32];
        crypto_core_hsalsa20(s,n,k,sigma);
        return crypto_stream_salsa20_xor(c, m, d, n, 16, s);
    }

    private static void add1305(int[] h,int[] c)
    {
        int j,u = 0;
        for (j=0;j < 17;++j){
        u += h[j] + c[j];
        h[j] = u & 255;
        u >>= 8;
    }
    }

    private static int[] minusp = new int[] {
        5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 252
    } ;

    private static int crypto_onetimeauth(byte[] out, int outOff, byte[] m, int mOff, long n,byte[] k)
    {
        int s,i,j,u;
        int[] x = new int[17],r = new int[17],h = new int[17],c = new int[17],g = new int[17];

        for (j=0;j < 17;++j)
            r[j]= h[j] = 0;
        for (j=0;j < 16;++j)
            r[j] = 0xff & k[j];
        r[3]&=15;
        r[4]&=252;
        r[7]&=15;
        r[8]&=252;
        r[11]&=15;
        r[12]&=252;
        r[15]&=15;

        while (n > 0) {
            for (j=0;j < 17;++j)
                c[j] = 0;
            for (j = 0;(j < 16) && (j < n);++j)
                c[j] = 0xff & m[mOff + j];
            c[j] = 1;
            mOff += j; n -= j;
            add1305(h,c);
            for (i=0;i < 17;++i){
                x[i] = 0;
                for (j=0;j < 17; ++j)
                    x[i] += h[j] * ((j <= i)? r[i - j] : 320 * r[i + 17 - j]);
            }
            for (i=0;i < 17;++i)
                h[i] = x[i];
            u = 0;
            for (j=0;j < 16;++j){
                u += h[j];
                h[j] = u & 255;
                u >>= 8;
            }
            u += h[16]; h[16] = u & 3;
            u = 5 * (u >> 2);
            for (j=0;j < 16;++j){
                u += h[j];
                h[j] = u & 255;
                u >>= 8;
            }
            u += h[16]; h[16] = u;
        }

        for (j=0;j < 17;++j)g[j] = h[j];
        add1305(h,minusp);
        s = -(h[16] >> 7);
        for (j=0;j < 17;++j)h[j] ^= s & (g[j] ^ h[j]);

        for (j=0;j < 16;++j)
            c[j] = 0xff & k[j + 16];
        c[16] = 0;
        add1305(h,c);
        for (j=0;j < 16;++j)out[outOff + j] = (byte)h[j];
        return 0;
    }

    private static int crypto_onetimeauth_verify(byte[] h, int hOff, byte[] m, int mOff, long n,byte[] k)
    {
        byte[] x = new byte[16];
        crypto_onetimeauth(x, 0, m, mOff, n,k);
        return crypto_verify_16(h, hOff, x);
    }

    private static int crypto_secretbox(byte[] c,byte[] m,long d,byte[] n,byte[] k)
    {
        int i;
        if (d < 32) return -1;
        crypto_stream_xor(c,m,d,n,k);
        crypto_onetimeauth(c, 16, c, 32, d - 32, c);
        for (i=0;i < 16;++i)c[i] = 0;
        return 0;
    }

    private static int crypto_secretbox_open(byte[] m,byte[] c,long d,byte[] n,byte[] k)
    {
        int i;
        byte[] x = new byte[32];
        if (d < 32) return -1;
        crypto_stream(x,32,n,k);
        if (crypto_onetimeauth_verify(c, 16,c, 32,d - 32,x) != 0) return -1;
        crypto_stream_xor(m,c,d,n,k);
        for (i=0;i < 32;++i)m[i] = 0;
        return 0;
    }

    private static void set25519(long[] /*gf*/ r, long[] /*gf*/ a)
    {
        int i;
        for (i=0;i < 16;++i)r[i]=a[i];
    }

    private static void car25519(long[] /*gf*/ o, int oOff)
    {
        for (int i=0;i < 16;++i){
            o[oOff + i]+=(1<<16);
            long c=o[oOff + i]>>16;
            o[oOff + (i+1) * (i<15 ? 1:0)] += c - 1 + 37 * (c-1) * (i==15 ? 1 : 0);
            o[oOff + i]-=c<<16;
        }
    }

    private static void sel25519(long[] /*gf*/ p,long[] /*gf*/ q,int b)
    {
        long t,c=~(b-1);
        int i;
        for (i=0;i < 16;++i){
        t= c&(p[i]^q[i]);
        p[i]^=t;
        q[i]^=t;
    }
    }

    private static void pack25519(byte[] o,long[] /*gf*/ n, int nOff)
    {
        int i,j,b;
        long[] /*gf*/ m = new long[GF_LEN],t = new long[GF_LEN];
        for (i=0;i < 16;++i)t[i]=n[nOff+i];
        car25519(t, 0);
        car25519(t, 0);
        car25519(t, 0);
        for (j=0;j < 2;++j){
            m[0]=t[0]-0xffed;
            for(i=1;i<15;i++) {
                m[i]=t[i]-0xffff-((m[i-1]>>16)&1);
                m[i-1]&=0xffff;
            }
            m[15]=t[15]-0x7fff-((m[14]>>16)&1);
            b=(int)((m[15]>>16)&1);
            m[14]&=0xffff;
            sel25519(t,m,1-b);
        }
        for (i=0;i < 16;++i){
            o[2*i]=(byte)t[i];
            o[2*i+1]=(byte)(t[i]>>8);
        }
    }

    private static int neq25519(long[] /*gf*/ a, long[] /*gf*/ b)
    {
        byte[] c = new byte[32],d = new byte[32];
        pack25519(c,a, 0);
        pack25519(d,b, 0);
        return crypto_verify_32(c,d);
    }

    private static byte par25519(long[] /*gf*/ a)
    {
        byte[] d = new byte[32];
        pack25519(d,a, 0);
        return (byte)(d[0]&1);
    }

    private static void unpack25519(long[] /*gf*/ o, byte[] n)
    {
        int i;
        for (i=0;i < 16;++i)
            o[i] = (0xff & n[2*i])+((0xffL & n[2*i+1])<<8);
        o[15]&=0x7fff;
    }

    private static void A(long[] /*gf*/ o,long[] /*gf*/ a,long[] /*gf*/ b)
    {
        int i;
        for (i=0;i < 16;++i)o[i]=a[i]+b[i];
    }

    private static void Z(long[] /*gf*/ o,long[] /*gf*/ a,long[] /*gf*/ b)
    {
        int i;
        for (i=0;i < 16;++i)o[i]=a[i]-b[i];
    }

    private static void M(long[] /*gf*/ o, int oOff, long[] /*gf*/ a, int aOff, long[] /*gf*/ b, int bOff)
    {
        long[] t = new long[31];
        for (int i=0;i < 31;++i)t[i]=0;
        for (int i=0;i < 16; ++i) for(int j=0; j <16;++j)t[i+j]+=a[aOff + i]*b[bOff + j];
        for (int i=0;i < 15;++i)t[i]+=38*t[i+16];
        for (int i=0;i < 16;++i)o[oOff + i]=t[i];
        car25519(o, oOff);
        car25519(o, oOff);
    }

    private static void S(long[] /*gf*/ o,long[] /*gf*/ a)
    {
        M(o, 0, a, 0, a, 0);
    }

    private static void inv25519(long[] /*gf*/ o, int oOff, long[] /*gf*/ i, int iOff)
    {
        long[] /*gf*/ c = new long[GF_LEN];
        int a;
        for (a=0;a < 16;++a)c[a]=i[iOff + a];
        for(a=253;a>=0;a--) {
            S(c,c);
            if(a!=2&&a!=4) M(c, 0, c, 0, i, iOff);
        }
        for (a=0;a < 16;++a)o[oOff + a]=c[a];
    }

    private static void pow2523(long[] /*gf*/ o,long[] /*gf*/ i)
    {
        long[] /*gf*/ c = new long[GF_LEN];
        int a;
        for (a=0;a < 16;++a)c[a]=i[a];
        for(a=250;a>=0;a--) {
            S(c,c);
            if(a!=1) M(c, 0, c, 0, i, 0);
        }
        for (a=0;a < 16;++a)o[a]=c[a];
    }

    private static int crypto_scalarmult(byte[] q,byte[] n,byte[] p)
    {
        byte[] z = new byte[32];
        long[] x = new long[80];
        int r;
        int i;
        long[] /*gf*/ a = new long[GF_LEN],b = new long[GF_LEN],c = new long[GF_LEN],
                d = new long[GF_LEN],e = new long[GF_LEN],f = new long[GF_LEN];
        for (i=0;i < 31;++i)
            z[i] = n[i];
        z[31] = (byte)((n[31]&127)|64);
        z[0] &= 248;
        unpack25519(x,p);
        for (i=0;i < 16;++i){
            b[i]=x[i];
            d[i]=a[i]=c[i]=0;
        }
        a[0]=d[0]=1;

        for(i=254;i>=0;--i) {
            r=( (0xff & z[i>>3]) >> (i&7))&1;
            sel25519(a,b,r);
            sel25519(c,d,r);
            A(e,a,c);
            Z(a,a,c);
            A(c,b,d);
            Z(b,b,d);
            S(d,e);
            S(f,a);
            M(a, 0, c, 0, a, 0);
            M(c, 0, b, 0, e, 0);
            A(e,a,c);
            Z(a,a,c);
            S(b, a);
            Z(c,d,f);
            M(a, 0, c, 0, _121665, 0);
            A(a, a, d);
            M(c, 0, c, 0, a, 0);
            M(a, 0, d, 0, f, 0);
            M(d, 0, b, 0, x, 0);
            S(b,e);
            sel25519(a,b,r);
            sel25519(c,d,r);
        }
        for (i=0;i < 16;++i){
            x[i+16]=a[i];
            x[i+32]=c[i];
            x[i+48]=b[i];
            x[i+64]=d[i];
        }

        inv25519(x, 32,x, 32);

        M(x, 16,x, 16, x, 32);

        pack25519(q,x, 16);
        return 0;
    }

    private static int crypto_box_beforenm(byte[] k,byte[] y,byte[] x)
    {
        byte[] s = new byte[32];
        crypto_scalarmult(s, x, y);
        return crypto_core_hsalsa20(k,_0,s,sigma);
    }

    private static int crypto_box_afternm(byte[] c,byte[] m,long d,byte[] n,byte[] k)
    {
        return crypto_secretbox(c, m, d, n, k);
    }

    private static int crypto_box_open_afternm(byte[] m,byte[] c,long d,byte[] n,byte[] k)
    {
        return crypto_secretbox_open(m, c, d, n, k);
    }

    private static int crypto_box(byte[] c,byte[] m,long d,byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey)
    {
        byte[] k = new byte[32];
        crypto_box_beforenm(k, theirPublicBoxingKey, ourSecretBoxingKey);
        return crypto_box_afternm(c, m, d, nonce, k);
    }

    private static int crypto_box_open(byte[] m,byte[] c,long d,byte[] n,byte[] y,byte[] x)
    {
        byte[] k = new byte[32];
        crypto_box_beforenm(k,y,x);
        return crypto_box_open_afternm(m, c, d, n, k);
    }

    private static int crypto_hash(byte[] out, byte[] m, int n) {
        int[] hh = new int[8], hl = new int[8];
        byte[] x = new byte[256];
        int i, b = n;

        hh[0] = 0x6a09e667;
        hh[1] = 0xbb67ae85;
        hh[2] = 0x3c6ef372;
        hh[3] = 0xa54ff53a;
        hh[4] = 0x510e527f;
        hh[5] = 0x9b05688c;
        hh[6] = 0x1f83d9ab;
        hh[7] = 0x5be0cd19;

        hl[0] = 0xf3bcc908;
        hl[1] = 0x84caa73b;
        hl[2] = 0xfe94f82b;
        hl[3] = 0x5f1d36f1;
        hl[4] = 0xade682d1;
        hl[5] = 0x2b3e6c1f;
        hl[6] = 0xfb41bd6b;
        hl[7] = 0x137e2179;

        crypto_hashblocks_hl(hh, hl, m, n);
        n %= 128;

        for (i = 0; i < n; i++) x[i] = m[b-n+i];
        x[n] = (byte)128;

        n = 256-128*(n<112?1:0);
        x[n-9] = 0;
        jsts64(x, n - 8, (b / 0x20000000), b << 3);
        crypto_hashblocks_hl(hh, hl, x, n);

        for (i = 0; i < 8; i++) jsts64(out, 8 * i, hh[i], hl[i]);

        return 0;
    }

    private static void jsts64(byte[] x, int i, int h, int l) {
        x[i]   = (byte)(h >> 24);
        x[i+1] = (byte)(h >> 16);
        x[i+2] = (byte)(h >>  8);
        x[i+3] = (byte)h;
        x[i+4] = (byte)(l >> 24);
        x[i+5] = (byte)(l >> 16);
        x[i+6] = (byte)(l >>  8);
        x[i+7] = (byte)l;
    }

    private static int[] jsK = new int[]{
            0x428a2f98, 0xd728ae22, 0x71374491, 0x23ef65cd,
            0xb5c0fbcf, 0xec4d3b2f, 0xe9b5dba5, 0x8189dbbc,
            0x3956c25b, 0xf348b538, 0x59f111f1, 0xb605d019,
            0x923f82a4, 0xaf194f9b, 0xab1c5ed5, 0xda6d8118,
            0xd807aa98, 0xa3030242, 0x12835b01, 0x45706fbe,
            0x243185be, 0x4ee4b28c, 0x550c7dc3, 0xd5ffb4e2,
            0x72be5d74, 0xf27b896f, 0x80deb1fe, 0x3b1696b1,
            0x9bdc06a7, 0x25c71235, 0xc19bf174, 0xcf692694,
            0xe49b69c1, 0x9ef14ad2, 0xefbe4786, 0x384f25e3,
            0x0fc19dc6, 0x8b8cd5b5, 0x240ca1cc, 0x77ac9c65,
            0x2de92c6f, 0x592b0275, 0x4a7484aa, 0x6ea6e483,
            0x5cb0a9dc, 0xbd41fbd4, 0x76f988da, 0x831153b5,
            0x983e5152, 0xee66dfab, 0xa831c66d, 0x2db43210,
            0xb00327c8, 0x98fb213f, 0xbf597fc7, 0xbeef0ee4,
            0xc6e00bf3, 0x3da88fc2, 0xd5a79147, 0x930aa725,
            0x06ca6351, 0xe003826f, 0x14292967, 0x0a0e6e70,
            0x27b70a85, 0x46d22ffc, 0x2e1b2138, 0x5c26c926,
            0x4d2c6dfc, 0x5ac42aed, 0x53380d13, 0x9d95b3df,
            0x650a7354, 0x8baf63de, 0x766a0abb, 0x3c77b2a8,
            0x81c2c92e, 0x47edaee6, 0x92722c85, 0x1482353b,
            0xa2bfe8a1, 0x4cf10364, 0xa81a664b, 0xbc423001,
            0xc24b8b70, 0xd0f89791, 0xc76c51a3, 0x0654be30,
            0xd192e819, 0xd6ef5218, 0xd6990624, 0x5565a910,
            0xf40e3585, 0x5771202a, 0x106aa070, 0x32bbd1b8,
            0x19a4c116, 0xb8d2d0c8, 0x1e376c08, 0x5141ab53,
            0x2748774c, 0xdf8eeb99, 0x34b0bcb5, 0xe19b48a8,
            0x391c0cb3, 0xc5c95a63, 0x4ed8aa4a, 0xe3418acb,
            0x5b9cca4f, 0x7763e373, 0x682e6ff3, 0xd6b2b8a3,
            0x748f82ee, 0x5defb2fc, 0x78a5636f, 0x43172f60,
            0x84c87814, 0xa1f0ab72, 0x8cc70208, 0x1a6439ec,
            0x90befffa, 0x23631e28, 0xa4506ceb, 0xde82bde9,
            0xbef9a3f7, 0xb2c67915, 0xc67178f2, 0xe372532b,
            0xca273ece, 0xea26619c, 0xd186b8c7, 0x21c0c207,
            0xeada7dd6, 0xcde0eb1e, 0xf57d4f7f, 0xee6ed178,
            0x06f067aa, 0x72176fba, 0x0a637dc5, 0xa2c898a6,
            0x113f9804, 0xbef90dae, 0x1b710b35, 0x131c471b,
            0x28db77f5, 0x23047d84, 0x32caab7b, 0x40c72493,
            0x3c9ebe0a, 0x15c9bebc, 0x431d67c4, 0x9c100d4c,
            0x4cc5d4be, 0xcb3e42b6, 0x597f299c, 0xfc657e2a,
            0x5fcb6fab, 0x3ad6faec, 0x6c44198c, 0x4a475817
    };

    private static int crypto_hashblocks_hl(int[] hh, int[] hl, byte[] m, int n) {
        int[] wh = new int[16], wl = new int[16];
        int bh0, bh1, bh2, bh3, bh4, bh5, bh6, bh7,
                bl0, bl1, bl2, bl3, bl4, bl5, bl6, bl7,
                th, tl, i, j, h, l, a, b, c, d;

        int ah0 = hh[0],
                ah1 = hh[1],
                ah2 = hh[2],
                ah3 = hh[3],
                ah4 = hh[4],
                ah5 = hh[5],
                ah6 = hh[6],
                ah7 = hh[7],

                al0 = hl[0],
                al1 = hl[1],
                al2 = hl[2],
                al3 = hl[3],
                al4 = hl[4],
                al5 = hl[5],
                al6 = hl[6],
                al7 = hl[7];

        int pos = 0;
        while (n >= 128) {
            for (i = 0; i < 16; i++) {
                j = 8 * i + pos;
                wh[i] = ((m[j+0] & 0xff) << 24) | ((m[j+1] & 0xff) << 16) | ((m[j+2] & 0xff) << 8) | (m[j+3] & 0xff);
                wl[i] = ((m[j+4] & 0xff) << 24) | ((m[j+5] & 0xff) << 16) | ((m[j+6] & 0xff) << 8) | (m[j+7] & 0xff);
            }
            for (i = 0; i < 80; i++) {
                bh0 = ah0;
                bh1 = ah1;
                bh2 = ah2;
                bh3 = ah3;
                bh4 = ah4;
                bh5 = ah5;
                bh6 = ah6;
                bh7 = ah7;

                bl0 = al0;
                bl1 = al1;
                bl2 = al2;
                bl3 = al3;
                bl4 = al4;
                bl5 = al5;
                bl6 = al6;
                bl7 = al7;

                // add
                h = ah7;
                l = al7;

                a = l & 0xffff; b = l >>> 16;
                c = h & 0xffff; d = h >>> 16;

                // Sigma1
                h = ((ah4 >>> 14) | (al4 << (32-14))) ^ ((ah4 >>> 18) | (al4 << (32-18))) ^ ((al4 >>> (41-32)) | (ah4 << (32-(41-32))));
                l = ((al4 >>> 14) | (ah4 << (32-14))) ^ ((al4 >>> 18) | (ah4 << (32-18))) ^ ((ah4 >>> (41-32)) | (al4 << (32-(41-32))));

                a += l & 0xffff; b += l >>> 16;
                c += h & 0xffff; d += h >>> 16;

                // Ch
                h = (ah4 & ah5) ^ (~ah4 & ah6);
                l = (al4 & al5) ^ (~al4 & al6);

                a += l & 0xffff; b += l >>> 16;
                c += h & 0xffff; d += h >>> 16;

                // K
                h = jsK[i*2];
                l = jsK[i*2+1];

                a += l & 0xffff; b += l >>> 16;
                c += h & 0xffff; d += h >>> 16;

                // w
                h = wh[i%16];
                l = wl[i%16];

                a += l & 0xffff; b += l >>> 16;
                c += h & 0xffff; d += h >>> 16;

                b += a >>> 16;
                c += b >>> 16;
                d += c >>> 16;

                th = c & 0xffff | d << 16;
                tl = a & 0xffff | b << 16;

                // add
                h = th;
                l = tl;

                a = l & 0xffff; b = l >>> 16;
                c = h & 0xffff; d = h >>> 16;

                // Sigma0
                h = ((ah0 >>> 28) | (al0 << (32-28))) ^ ((al0 >>> (34-32)) | (ah0 << (32-(34-32)))) ^ ((al0 >>> (39-32)) | (ah0 << (32-(39-32))));
                l = ((al0 >>> 28) | (ah0 << (32-28))) ^ ((ah0 >>> (34-32)) | (al0 << (32-(34-32)))) ^ ((ah0 >>> (39-32)) | (al0 << (32-(39-32))));

                a += l & 0xffff; b += l >>> 16;
                c += h & 0xffff; d += h >>> 16;

                // Maj
                h = (ah0 & ah1) ^ (ah0 & ah2) ^ (ah1 & ah2);
                l = (al0 & al1) ^ (al0 & al2) ^ (al1 & al2);

                a += l & 0xffff; b += l >>> 16;
                c += h & 0xffff; d += h >>> 16;

                b += a >>> 16;
                c += b >>> 16;
                d += c >>> 16;

                bh7 = (c & 0xffff) | (d << 16);
                bl7 = (a & 0xffff) | (b << 16);

                // add
                h = bh3;
                l = bl3;

                a = l & 0xffff; b = l >>> 16;
                c = h & 0xffff; d = h >>> 16;

                h = th;
                l = tl;

                a += l & 0xffff; b += l >>> 16;
                c += h & 0xffff; d += h >>> 16;

                b += a >>> 16;
                c += b >>> 16;
                d += c >>> 16;

                bh3 = (c & 0xffff) | (d << 16);
                bl3 = (a & 0xffff) | (b << 16);

                ah1 = bh0;
                ah2 = bh1;
                ah3 = bh2;
                ah4 = bh3;
                ah5 = bh4;
                ah6 = bh5;
                ah7 = bh6;
                ah0 = bh7;

                al1 = bl0;
                al2 = bl1;
                al3 = bl2;
                al4 = bl3;
                al5 = bl4;
                al6 = bl5;
                al7 = bl6;
                al0 = bl7;

                if (i%16 == 15) {
                    for (j = 0; j < 16; j++) {
                        // add
                        h = wh[j];
                        l = wl[j];

                        a = l & 0xffff; b = l >>> 16;
                        c = h & 0xffff; d = h >>> 16;

                        h = wh[(j+9)%16];
                        l = wl[(j+9)%16];

                        a += l & 0xffff; b += l >>> 16;
                        c += h & 0xffff; d += h >>> 16;

                        // sigma0
                        th = wh[(j+1)%16];
                        tl = wl[(j+1)%16];
                        h = ((th >>> 1) | (tl << (32-1))) ^ ((th >>> 8) | (tl << (32-8))) ^ (th >>> 7);
                        l = ((tl >>> 1) | (th << (32-1))) ^ ((tl >>> 8) | (th << (32-8))) ^ ((tl >>> 7) | (th << (32-7)));

                        a += l & 0xffff; b += l >>> 16;
                        c += h & 0xffff; d += h >>> 16;

                        // sigma1
                        th = wh[(j+14)%16];
                        tl = wl[(j+14)%16];
                        h = ((th >>> 19) | (tl << (32-19))) ^ ((tl >>> (61-32)) | (th << (32-(61-32)))) ^ (th >>> 6);
                        l = ((tl >>> 19) | (th << (32-19))) ^ ((th >>> (61-32)) | (tl << (32-(61-32)))) ^ ((tl >>> 6) | (th << (32-6)));

                        a += l & 0xffff; b += l >>> 16;
                        c += h & 0xffff; d += h >>> 16;

                        b += a >>> 16;
                        c += b >>> 16;
                        d += c >>> 16;

                        wh[j] = (c & 0xffff) | (d << 16);
                        wl[j] = (a & 0xffff) | (b << 16);
                    }
                }
            }

            // add
            h = ah0;
            l = al0;

            a = l & 0xffff; b = l >>> 16;
            c = h & 0xffff; d = h >>> 16;

            h = hh[0];
            l = hl[0];

            a += l & 0xffff; b += l >>> 16;
            c += h & 0xffff; d += h >>> 16;

            b += a >>> 16;
            c += b >>> 16;
            d += c >>> 16;

            hh[0] = ah0 = (c & 0xffff) | (d << 16);
            hl[0] = al0 = (a & 0xffff) | (b << 16);

            h = ah1;
            l = al1;

            a = l & 0xffff; b = l >>> 16;
            c = h & 0xffff; d = h >>> 16;

            h = hh[1];
            l = hl[1];

            a += l & 0xffff; b += l >>> 16;
            c += h & 0xffff; d += h >>> 16;

            b += a >>> 16;
            c += b >>> 16;
            d += c >>> 16;

            hh[1] = ah1 = (c & 0xffff) | (d << 16);
            hl[1] = al1 = (a & 0xffff) | (b << 16);

            h = ah2;
            l = al2;

            a = l & 0xffff; b = l >>> 16;
            c = h & 0xffff; d = h >>> 16;

            h = hh[2];
            l = hl[2];

            a += l & 0xffff; b += l >>> 16;
            c += h & 0xffff; d += h >>> 16;

            b += a >>> 16;
            c += b >>> 16;
            d += c >>> 16;

            hh[2] = ah2 = (c & 0xffff) | (d << 16);
            hl[2] = al2 = (a & 0xffff) | (b << 16);

            h = ah3;
            l = al3;

            a = l & 0xffff; b = l >>> 16;
            c = h & 0xffff; d = h >>> 16;

            h = hh[3];
            l = hl[3];

            a += l & 0xffff; b += l >>> 16;
            c += h & 0xffff; d += h >>> 16;

            b += a >>> 16;
            c += b >>> 16;
            d += c >>> 16;

            hh[3] = ah3 = (c & 0xffff) | (d << 16);
            hl[3] = al3 = (a & 0xffff) | (b << 16);

            h = ah4;
            l = al4;

            a = l & 0xffff; b = l >>> 16;
            c = h & 0xffff; d = h >>> 16;

            h = hh[4];
            l = hl[4];

            a += l & 0xffff; b += l >>> 16;
            c += h & 0xffff; d += h >>> 16;

            b += a >>> 16;
            c += b >>> 16;
            d += c >>> 16;

            hh[4] = ah4 = (c & 0xffff) | (d << 16);
            hl[4] = al4 = (a & 0xffff) | (b << 16);

            h = ah5;
            l = al5;

            a = l & 0xffff; b = l >>> 16;
            c = h & 0xffff; d = h >>> 16;

            h = hh[5];
            l = hl[5];

            a += l & 0xffff; b += l >>> 16;
            c += h & 0xffff; d += h >>> 16;

            b += a >>> 16;
            c += b >>> 16;
            d += c >>> 16;

            hh[5] = ah5 = (c & 0xffff) | (d << 16);
            hl[5] = al5 = (a & 0xffff) | (b << 16);

            h = ah6;
            l = al6;

            a = l & 0xffff; b = l >>> 16;
            c = h & 0xffff; d = h >>> 16;

            h = hh[6];
            l = hl[6];

            a += l & 0xffff; b += l >>> 16;
            c += h & 0xffff; d += h >>> 16;

            b += a >>> 16;
            c += b >>> 16;
            d += c >>> 16;

            hh[6] = ah6 = (c & 0xffff) | (d << 16);
            hl[6] = al6 = (a & 0xffff) | (b << 16);

            h = ah7;
            l = al7;

            a = l & 0xffff; b = l >>> 16;
            c = h & 0xffff; d = h >>> 16;

            h = hh[7];
            l = hl[7];

            a += l & 0xffff; b += l >>> 16;
            c += h & 0xffff; d += h >>> 16;

            b += a >>> 16;
            c += b >>> 16;
            d += c >>> 16;

            hh[7] = ah7 = (c & 0xffff) | (d << 16);
            hl[7] = al7 = (a & 0xffff) | (b << 16);

            pos += 128;
            n -= 128;
        }

        return n;
    }

    private static void add(long[][] /*gf*/ p/*[4]*/,long[][] /*gf*/ q/*[4]*/)
    {
        long[] /*gf*/ a=new long[GF_LEN],b=new long[GF_LEN],c=new long[GF_LEN],
                d=new long[GF_LEN],t=new long[GF_LEN],e=new long[GF_LEN],
                f=new long[GF_LEN],g=new long[GF_LEN],h=new long[GF_LEN];

        Z(a, p[1], p[0]);
        Z(t, q[1], q[0]);
        M(a, 0, a, 0, t, 0);
        A(b, p[0], p[1]);
        A(t, q[0], q[1]);
        M(b, 0, b, 0, t, 0);
        M(c, 0, p[3], 0, q[3], 0);
        M(c, 0, c, 0, D2, 0);
        M(d, 0, p[2], 0, q[2], 0);
        A(d, d, d);
        Z(e, b, a);
        Z(f, d, c);
        A(g, d, c);
        A(h, b, a);

        M(p[0], 0, e, 0, f, 0);
        M(p[1], 0, h, 0, g, 0);
        M(p[2], 0, g, 0, f, 0);
        M(p[3], 0, e, 0, h, 0);
    }

    private static void cswap(long[][] /*gf*/ p/*[4]*/,long[][] /*gf*/ q/*[4]*/,byte b)
    {
        int i;
        for(i=0; i < 4; i++)
        sel25519(p[i],q[i],b & 0xff);
    }

    private static void pack(byte[] r,long[][] /*gf*/ p/*[4]*/)
    {
        long[] /*gf*/ tx = new long[GF_LEN], ty = new long[GF_LEN], zi = new long[GF_LEN];
        inv25519(zi, 0, p[2], 0);
        M(tx, 0, p[0], 0, zi, 0);
        M(ty, 0, p[1], 0, zi, 0);
        pack25519(r, ty, 0);
        r[31] ^= par25519(tx) << 7;
    }

    private static void scalarmult(long[][] /*gf*/ p/*[4]*/,long[][] /*gf*/ q/*[4]*/,byte[] s, int sOff)
    {
        int i;
        set25519(p[0], gf0);
        set25519(p[1], gf1);
        set25519(p[2], gf1);
        set25519(p[3], gf0);
        for (i = 255;i >= 0;--i) {
            byte b = (byte)(( (0xff & s[sOff + i/8]) >> (i&7))&1);
            cswap(p,q,b);
            add(q,p);
            add(p,p);
            cswap(p,q,b);
        }
    }

    private static void scalarbase(long[][] /*gf*/ p/*[4]*/,byte[] s,  int sOff)
    {
        long[][] /*gf*/ q = new long[4][16];
        set25519(q[0],X);
        set25519(q[1],Y);
        set25519(q[2],gf1);
        M(q[3], 0, X, 0, Y, 0);
        scalarmult(p,q,s, sOff);
    }

    private static long[] L = {0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,
            0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0x10};

    private static void modL(byte[] r, int rOff, long[] x/*[64]*/)
    {
        long carry;
        int i,j;
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
        for (j=0;j < 32;++j){
        x[j] += carry - (x[31] >> 4) * L[j];
        carry = x[j] >> 8;
        x[j] &= 255;
    }
        for (j=0;j < 32;++j)x[j] -= carry * L[j];
        for (i=0;i < 32;++i){
        x[i+1] += x[i] >> 8;
        r[rOff + i] = (byte)(x[i] & 255);
    }
    }

    private static void reduce(byte[] r)
    {
        long[] x = new long[64];
        for (int i=0;i < 64; i++) x[i] = 0xff & r[i];
        for (int i=0;i < 64;++i)r[i] = 0;
        modL(r, 0, x);
    }

    private static int crypto_sign(byte[] sm, byte[] m,int n,byte[] sk)
    {
        byte[] d = new byte[64],h = new byte[64],r = new byte[64];
        long[] x = new long[64];
        long[][] /*gf*/ p/*[4]*/ = new long[4][GF_LEN];

        crypto_hash(d, sk, 32);
        d[0] &= 248;
        d[31] &= 127;
        d[31] |= 64;

//        smlen[0] = n+64;
        for (int i=0;i < n;++i)sm[64 + i] = m[i];
        for (int i=0;i < 32;++i)sm[32 + i] = d[32 + i];
        crypto_hash(r, Arrays.copyOfRange(sm, 32, sm.length), n + 32);
        reduce(r);
        scalarbase(p, r, 0);
        pack(sm, p);

        for (int i=0;i < 32;++i)sm[i+32] = sk[i+32];
        crypto_hash(h, sm, n + 64);
        reduce(h);

        for (int i=0;i < 64;++i) x[i] = 0;
        for (int i=0;i < 32; ++i) x[i] = 0xff & r[i];
        for (int i=0;i < 32; ++i) for(int j=0; j < 32; ++j) x[i+j] += (0xff & h[i]) * (0xff & d[j]);
        modL(sm, 32,x);

        return 0;
    }

    private static int unpackneg(long[][] /*gf*/ r/*[4]*/,byte[] p/*[32]*/)
    {
        long[] /*gf*/ t = new long[GF_LEN], chk = new long[GF_LEN], num = new long[GF_LEN], den = new long[GF_LEN],
                den2 = new long[GF_LEN], den4 = new long[GF_LEN], den6 = new long[GF_LEN];
        set25519(r[2],gf1);
        unpack25519(r[1],p);
        S(num,r[1]);
        M(den, 0, num, 0, D, 0);
        Z(num,num,r[2]);
        A(den,r[2],den);

        S(den2,den);
        S(den4,den2);
        M(den6, 0, den4, 0, den2, 0);
        M(t, 0, den6, 0, num, 0);
        M(t, 0, t, 0, den, 0);

        pow2523(t,t);
        M(t, 0, t, 0, num, 0);
        M(t, 0, t, 0, den, 0);
        M(t, 0, t, 0, den, 0);
        M(r[0], 0, t, 0, den, 0);

        S(chk,r[0]);
        M(chk, 0, chk, 0, den, 0);
        if (neq25519(chk, num) != 0) M(r[0], 0, r[0], 0, I, 0);

        S(chk,r[0]);
        M(chk, 0, chk, 0, den, 0);
        if (neq25519(chk, num) != 0) return -1;

        if (par25519(r[0]) == ( (0xff & p[31]) >> 7)) Z(r[0],gf0,r[0]);

        M(r[3], 0, r[0], 0, r[1], 0);
        return 0;
    }

    private static int crypto_sign_open(byte[] m, byte[] sm, int n, byte[] pk)
    {
        int i;
        byte[] t = new byte[32],h = new byte[64];
        long[][] /*gf*/ p = new long[4][GF_LEN],q = new long[4][GF_LEN];

//        mlen[0] = -1;
        if (n < 64) return -1;

        if (unpackneg(q,pk) != 0) return -1;

        for (i=0;i < n;++i) m[i] = sm[i];
        for (i=0;i < 32;++i) m[i+32] = pk[i];
        crypto_hash(h, m, n);
        reduce(h);
        scalarmult(p, q, h, 0);

        scalarbase(q, sm, 32);
        add(p, q);
        pack(t, p);

        n -= 64;
        if (crypto_verify_32(sm, t) != 0) {
            for (i=0;i < n;++i)m[i] = 0;
            return -1;
        }

        for (i=0;i < n;++i)m[64 + i] = sm[i + 64];
//        mlen[0] = n;
        return 0;
    }

    private static final Random prng = getSecureRandom();

    private static SecureRandom getSecureRandom() {
        return new SecureRandom();
    }

    private static void randombytes(byte[] b, int len) {
        byte[] r = new byte[len];
        prng.nextBytes(r);
        System.arraycopy(r, 0, b, 0, len);
    }

    public static void randomBytes(byte[] b, int offset, int len) {
        byte[] r = new byte[len];
        prng.nextBytes(r);
        System.arraycopy(r, 0, b, offset, len);
    }
}
