package peergos.crypto;

import peergos.util.ArrayOps;

import java.util.Random;

public class OurTweetNaCl {

    public static final int crypto_auth_hmacsha512256_tweet_BYTES = 32;
    public static final int crypto_auth_hmacsha512256_tweet_KEYBYTES = 32;
    public static final int crypto_box_curve25519xsalsa20poly1305_tweet_PUBLICKEYBYTES = 32;
    public static final int crypto_box_curve25519xsalsa20poly1305_tweet_SECRETKEYBYTES = 32;
    public static final int crypto_box_curve25519xsalsa20poly1305_tweet_BEFORENMBYTES = 32;
    public static final int crypto_box_curve25519xsalsa20poly1305_tweet_NONCEBYTES = 24;
    public static final int crypto_box_curve25519xsalsa20poly1305_tweet_ZEROBYTES = 32;
    public static final int crypto_box_curve25519xsalsa20poly1305_tweet_BOXZEROBYTES = 16;
    public static final int crypto_core_salsa20_tweet_OUTPUTBYTES = 64;
    public static final int crypto_core_salsa20_tweet_INPUTBYTES = 16;
    public static final int crypto_core_salsa20_tweet_KEYBYTES = 32;
    public static final int crypto_core_salsa20_tweet_CONSTBYTES = 16;
    public static final int crypto_core_hsalsa20_tweet_OUTPUTBYTES = 32;
    public static final int crypto_core_hsalsa20_tweet_INPUTBYTES = 16;
    public static final int crypto_core_hsalsa20_tweet_KEYBYTES = 32;
    public static final int crypto_core_hsalsa20_tweet_CONSTBYTES = 16;
    public static final int crypto_hashblocks_sha512_tweet_STATEBYTES = 64;
    public static final int crypto_hashblocks_sha512_tweet_BLOCKBYTES = 128;
    public static final int crypto_hashblocks_sha256_tweet_STATEBYTES = 32;
    public static final int crypto_hashblocks_sha256_tweet_BLOCKBYTES = 64;
    public static final int crypto_hash_sha512_tweet_BYTES = 64;
    public static final int crypto_hash_sha256_tweet_BYTES = 32;
    public static final int crypto_onetimeauth_poly1305_tweet_BYTES = 16;
    public static final int crypto_onetimeauth_poly1305_tweet_KEYBYTES = 32;
    public static final int crypto_scalarmult_curve25519_tweet_BYTES = 32;
    public static final int crypto_scalarmult_curve25519_tweet_SCALARBYTES = 32;
    public static final int crypto_secretbox_xsalsa20poly1305_tweet_KEYBYTES = 32;
    public static final int crypto_secretbox_xsalsa20poly1305_tweet_NONCEBYTES = 24;
    public static final int crypto_secretbox_xsalsa20poly1305_tweet_ZEROBYTES = 32;
    public static final int crypto_secretbox_xsalsa20poly1305_tweet_BOXZEROBYTES = 16;
    public static final int crypto_sign_ed25519_tweet_BYTES = 64;
    public static final int crypto_sign_ed25519_tweet_PUBLICKEYBYTES = 32;
    public static final int crypto_sign_ed25519_tweet_SECRETKEYBYTES = 64;
    public static final int crypto_stream_xsalsa20_tweet_KEYBYTES = 32;
    public static final int crypto_stream_xsalsa20_tweet_NONCEBYTES = 24;
    public static final int crypto_stream_salsa20_tweet_KEYBYTES = 32;
    public static final int crypto_stream_salsa20_tweet_NONCEBYTES = 8;
    public static final int crypto_verify_16_tweet_BYTES = 16;
    public static final int crypto_verify_32_tweet_BYTES = 32;

    static byte[] _0 = new byte[16], _9 = new byte[32];
    static {
        _9[0] = 9;
    }
    private static final int GF_LEN = 16;
    static long[]  gf0 = new long[GF_LEN];
    static long[] gf1 = new long[GF_LEN]; static{gf1[0] = 1;}
    static long[]  _121665 = new long[GF_LEN]; static{_121665[0] = 0xDB41; _121665[1] =1;}
    static long[]  D = new long[]{0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203},
            D2 = new long[]{0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406},
            X = new long[]{0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169},
            Y = new long[]{0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666},
            I = new long[]{0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83};

    static int L32(int x,int c) { return (x << c) | ((x&0xffffffff) >>> (32 - c)); }

    static int ld32(byte[] x, int off)
    {
        int u = x[off + 3] & 0xff;
        u = (u<<8)|(x[off + 2] & 0xff);
        u = (u<<8)|(x[off + 1] & 0xff);
        return (u<<8)|(x[off + 0] & 0xff);
    }

    static long dl64(byte[] x, int xOff)
    {
        long u=0;
        for (int i=0;i < 8;++i)u=(u<<8)|(x[xOff + i]&0xff);
        return u;
    }

    static void st32(byte[] x, int off, int u)
    {
        int i;
        for (i=0;i < 4;++i){ x[off + i] = (byte)u; u >>= 8; }
    }

    static void ts64(byte[] x, int xOff, long u)
    {
        int i;
        for (i = 7;i >= 0;--i) { x[xOff + i] = (byte)u; u >>= 8; }
    }

    static int vn(byte[] x, int xOff, byte[] y,int n)
    {
        int i,d = 0;
        for (i=0;i < n;++i)d |= x[xOff + i]^y[i];
        return (1 & ((d - 1) >> 8)) - 1;
    }

    static int crypto_verify_16(byte[] x, int xOff, byte[] y)
    {
        return vn(x, xOff, y, 16);
    }

    static int crypto_verify_32(byte[] x,byte[] y)
    {
        return vn(x, 0, y,32);
    }

    static void core(byte[] out,byte[] in,byte[] k,byte[] c,int h)
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

    static int crypto_core_salsa20(byte[] out,byte[] in,byte[] k,byte[] c)
    {
        core(out,in,k,c,0);
        return 0;
    }

    static int crypto_core_hsalsa20(byte[] out,byte[] in,byte[] k,byte[] c)
    {
        core(out,in,k,c,1);
        return 0;
    }

    static byte[] sigma = { 101, 120, 112, 97, 110, 100, 32, 51, 50, 45, 98, 121, 116, 101, 32, 107 };

    static int crypto_stream_salsa20_xor(byte[] c,byte[] m,long b,byte[] n, int nOff, byte[] k)
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
            for (i=0;i < 64; ++i) c[i] = (byte)((m != null ? m[i]:0)^ x[i]);
            u = 1;
            for (i = 8;i < 16;++i) {
                u += (int) z[i];
                z[i] = (byte)u;
                u >>= 8;
            }
            b -= 64;
            cOff += 64;
            if (m != null) mOff += 64;
        }
        if (b != 0) {
            crypto_core_salsa20(x,z,k,sigma);
            for (i=0;i < b; i++) c[i] = (byte)((m != null ? m[i]:0)^ x[i]);
        }
        return 0;
    }

    static int crypto_stream_salsa20(byte[] c,long d,byte[] n, int nOff, byte[] k)
    {
        return crypto_stream_salsa20_xor(c,null,d,n, nOff, k);
    }

    static int crypto_stream(byte[] c,long d,byte[] n,byte[] k)
    {
        byte[] s = new byte[32];
        crypto_core_hsalsa20(s,n,k,sigma);
        return crypto_stream_salsa20(c, d, n, 16, s);
    }

    static int crypto_stream_xor(byte[] c,byte[] m,long d,byte[] n,byte[] k)
    {
        byte[] s = new byte[32];
        crypto_core_hsalsa20(s,n,k,sigma);
        return crypto_stream_salsa20_xor(c, m, d, n, 16, s);
    }

    static void add1305(int[] h,int[] c)
    {
        int j,u = 0;
        for (j=0;j < 17;++j){
        u += h[j] + c[j];
        h[j] = u & 255;
        u >>= 8;
    }
    }

    static int[] minusp = new int[] {
        5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 252
    } ;

    static int crypto_onetimeauth(byte[] out, int outOff, byte[] m, int mOff, long n,byte[] k)
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

    static int crypto_onetimeauth_verify(byte[] h, int hOff, byte[] m, int mOff, long n,byte[] k)
    {
        byte[] x = new byte[16];
        crypto_onetimeauth(x, 0, m, mOff, n,k);
        return crypto_verify_16(h, hOff, x);
    }

    static int crypto_secretbox(byte[] c,byte[] m,long d,byte[] n,byte[] k)
    {
        int i;
        if (d < 32) return -1;
        crypto_stream_xor(c,m,d,n,k);
        crypto_onetimeauth(c, 16,c, 32,d - 32,c);
        for (i=0;i < 16;++i)c[i] = 0;
        return 0;
    }

    static int crypto_secretbox_open(byte[] m,byte[] c,long d,byte[] n,byte[] k)
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

    static void set25519(long[] /*gf*/ r, long[] /*gf*/ a)
    {
        int i;
        for (i=0;i < 16;++i)r[i]=a[i];
    }

    static void car25519(long[] /*gf*/ o, int oOff)
    {
        for (int i=0;i < 16;++i){
            o[oOff + i]+=(1<<16);
            long c=o[oOff + i]>>16;
            o[oOff + (i+1) * (i<15 ? 1:0)] += c - 1 + 37 * (c-1) * (i==15 ? 1 : 0);
            o[oOff + i]-=c<<16;
        }
    }

    static void sel25519(long[] /*gf*/ p,long[] /*gf*/ q,int b)
    {
        long t,c=~(b-1);
        int i;
        for (i=0;i < 16;++i){
        t= c&(p[i]^q[i]);
        p[i]^=t;
        q[i]^=t;
    }
    }

    static void pack25519(byte[] o,long[] /*gf*/ n, int nOff)
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

    static int neq25519(long[] /*gf*/ a, long[] /*gf*/ b)
    {
        byte[] c = new byte[32],d = new byte[32];
        pack25519(c,a, 0);
        pack25519(d,b, 0);
        return crypto_verify_32(c,d);
    }

    static byte par25519(long[] /*gf*/ a)
    {
        byte[] d = new byte[32];
        pack25519(d,a, 0);
        return (byte)(d[0]&1);
    }

    static void unpack25519(long[] /*gf*/ o, byte[] n)
    {
        int i;
        for (i=0;i < 16;++i)
            o[i] = (0xff & n[2*i])+((0xffL & n[2*i+1])<<8);
        o[15]&=0x7fff;
    }

    static void A(long[] /*gf*/ o,long[] /*gf*/ a,long[] /*gf*/ b)
    {
        int i;
        for (i=0;i < 16;++i)o[i]=a[i]+b[i];
    }

    static void Z(long[] /*gf*/ o,long[] /*gf*/ a,long[] /*gf*/ b)
    {
        int i;
        for (i=0;i < 16;++i)o[i]=a[i]-b[i];
    }

    static void M(long[] /*gf*/ o, int oOff, long[] /*gf*/ a, int aOff, long[] /*gf*/ b, int bOff)
    {
        long[] t = new long[31];
        for (int i=0;i < 31;++i)t[i]=0;
        for (int i=0;i < 16; ++i) for(int j=0; j <16;++j)t[i+j]+=a[aOff + i]*b[bOff + j];
        for (int i=0;i < 15;++i)t[i]+=38*t[i+16];
        for (int i=0;i < 16;++i)o[oOff + i]=t[i];
        car25519(o, oOff);
        car25519(o, oOff);
    }

    static void S(long[] /*gf*/ o,long[] /*gf*/ a)
    {
        M(o, 0, a, 0, a, 0);
    }

    static void inv25519(long[] /*gf*/ o, int oOff, long[] /*gf*/ i, int iOff)
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

    static void pow2523(long[] /*gf*/ o,long[] /*gf*/ i)
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

    static int crypto_scalarmult(byte[] q,byte[] n,byte[] p)
    {
        byte[] z = new byte[32];
        long[] x = new long[80];
        int r;
        int i;
        long[] /*gf*/ a = new long[GF_LEN],b = new long[GF_LEN],c = new long[GF_LEN],
                d = new long[GF_LEN],e = new long[GF_LEN],f = new long[GF_LEN];
        for (i=0;i < 31;++i) z[i]=n[i];
        z[31]=(byte)((n[31]&127)|64);
        z[0]&=248;
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

    static int crypto_scalarmult_base(byte[] q,byte[] n)
    {
        return crypto_scalarmult(q,n,_9);
    }

    static int crypto_box_keypair(byte[] y,byte[] x, boolean isSeeded)
    {
        if (!isSeeded)
            randombytes(x,32);
        return crypto_scalarmult_base(y,x);
    }

    static int crypto_box_beforenm(byte[] k,byte[] y,byte[] x)
    {
        byte[] s = new byte[32];
        crypto_scalarmult(s, x, y);
        return crypto_core_hsalsa20(k,_0,s,sigma);
    }

    static int crypto_box_afternm(byte[] c,byte[] m,long d,byte[] n,byte[] k)
    {
        return crypto_secretbox(c, m, d, n, k);
    }

    static int crypto_box_open_afternm(byte[] m,byte[] c,long d,byte[] n,byte[] k)
    {
        return crypto_secretbox_open(m, c, d, n, k);
    }

    static int crypto_box(byte[] c,byte[] m,long d,byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey)
    {
        byte[] k = new byte[32];
        crypto_box_beforenm(k, theirPublicBoxingKey, ourSecretBoxingKey);
        return crypto_box_afternm(c, m, d, nonce, k);
    }

    static int crypto_box_open(byte[] m,byte[] c,long d,byte[] n,byte[] y,byte[] x)
    {
        byte[] k = new byte[32];
        crypto_box_beforenm(k,y,x);
        return crypto_box_open_afternm(m, c, d, n, k);
    }

    static long R(long x,int c) { return (x >> c) | (x << (64 - c)); }
    static long Ch(long x,long y,long z) { return (x & y) ^ (~x & z); }
    static long Maj(long x,long y,long z) { return (x & y) ^ (x & z) ^ (y & z); }
    static long Sigma0(long x) { return R(x,28) ^ R(x,34) ^ R(x,39); }
    static long Sigma1(long x) { return R(x,14) ^ R(x,18) ^ R(x,41); }
    static long sigma0(long x) { return R(x, 1) ^ R(x, 8) ^ (x >> 7); }
    static long sigma1(long x) { return R(x,19) ^ R(x,61) ^ (x >> 6); }

    static long[] K =
    {
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

    static int crypto_hashblocks(byte[] x,byte[] m, int n)
    {
        long[] z = new long[8],b = new long[8],a = new long[8],w = new long[16];
        long t;
        int i,j;

        for (i=0;i < 8;++i)z[i] = a[i] = dl64(x, 8 * i);

        int mOff = 0;
        while (n >= 128) {
            for (i=0;i < 16;++i)w[i] = dl64(m, mOff + 8 * i);

            for (i=0;i < 80;++i){
                for (j=0;j < 8;++j)b[j] = a[j];
                t = a[7] + Sigma1(a[4]) + Ch(a[4],a[5],a[6]) + K[i] + w[i%16];
                b[7] = t + Sigma0(a[0]) + Maj(a[0],a[1],a[2]);
                b[3] += t;
                for (j=0;j < 8;++j)a[(j+1)%8] = b[j];
                if (i%16 == 15)
                    for(j=0; j<16; ++j)
                w[j] += w[(j+9)%16] + sigma0(w[(j+1)%16]) + sigma1(w[(j+14)%16]);
            }

            for (i=0;i < 8;++i){ a[i] += z[i]; z[i] = a[i]; }

            mOff += 128;
            n -= 128;
        }

        for (i=0;i < 8;++i)ts64(x, 8*i,z[i]);

        return n;
    }

    static byte[] iv = {
        0x6a,0x09,(byte)0xe6,0x67,(byte)0xf3,(byte)0xbc,(byte)0xc9,0x08,
                (byte)0xbb,0x67,(byte)0xae,(byte)0x85,(byte)0x84,(byte)0xca,(byte)0xa7,0x3b,
                0x3c,0x6e,(byte)0xf3,0x72,(byte)0xfe,(byte)0x94,(byte)0xf8,0x2b,
                (byte)0xa5,0x4f,(byte)0xf5,0x3a,0x5f,0x1d,0x36,(byte)0xf1,
                0x51,0x0e,0x52,0x7f,(byte)0xad,(byte)0xe6,(byte)0x82,(byte)0xd1,
                (byte)0x9b,0x05,0x68,(byte)0x8c,0x2b,0x3e,0x6c,0x1f,
                0x1f,(byte)0x83,(byte)0xd9,(byte)0xab,(byte)0xfb,0x41,(byte)0xbd,0x6b,
                0x5b,(byte)0xe0,(byte)0xcd,0x19,0x13,0x7e,0x21,0x79
    } ;

    static int crypto_hash(byte[] out,byte[] m,int mOff, int n)
    {
        byte[] h = new byte[64],x = new byte[256];
        long b = n;

        for (int i=0;i < 64;++i)h[i] = iv[i];

        crypto_hashblocks(h,m,n);
        mOff += n;
        n &= 127;
        mOff -= n;

        for (int i=0;i < 256;++i) x[i] = 0;
        for (int i=0;i < n;++i) x[i] = m[mOff + i];
        x[n] = (byte)128;

        n = 256-128*(n<112 ? 1:0);
        x[n-9] = (byte)(b >> 61);
        ts64(x, n-8,b<<3);
        crypto_hashblocks(h,x,n);

        for (int i=0;i < 64;++i)out[i] = h[i];

        return 0;
    }

    static void add(long[][] /*gf*/ p/*[4]*/,long[][] /*gf*/ q/*[4]*/)
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

    static void cswap(long[][] /*gf*/ p/*[4]*/,long[][] /*gf*/ q/*[4]*/,byte b)
    {
        int i;
        for(i=0; i < 4; i++)
        sel25519(p[i],q[i],b);
    }

    static void pack(byte[] r,long[][] /*gf*/ p/*[4]*/)
    {
        long[] /*gf*/ tx = new long[GF_LEN], ty = new long[GF_LEN], zi = new long[GF_LEN];
        inv25519(zi, 0, p[2], 0);
        M(tx, 0, p[0], 0, zi, 0);
        M(ty, 0, p[1], 0, zi, 0);
        pack25519(r, ty, 0);
        r[31] ^= par25519(tx) << 7;
    }

    static void scalarmult(long[][] /*gf*/ p/*[4]*/,long[][] /*gf*/ q/*[4]*/,byte[] s, int sOff)
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

    static void scalarbase(long[][] /*gf*/ p/*[4]*/,byte[] s,  int sOff)
    {
        long[][] /*gf*/ q = new long[4][16];
        set25519(q[0],X);
        set25519(q[1],Y);
        set25519(q[2],gf1);
        M(q[3], 0, X, 0, Y, 0);
        scalarmult(p,q,s, sOff);
    }

    static int crypto_sign_keypair(byte[] pk, byte[] sk, boolean isSeeded)
    {
        byte[] d = new byte[64];
        long[][] /*gf*/ p = new long[4][GF_LEN];
        int i;

        if (!isSeeded)
            randombytes(sk, 32);
        crypto_hash(d, sk, 0, 32);
        d[0] &= 248;
        d[31] &= 127;
        d[31] |= 64;

        scalarbase(p,d, 0);
        pack(pk,p);

        for (i=0;i < 32;++i)sk[32 + i] = pk[i];
        return 0;
    }

    static long[] L = {0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,
            0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0x10};

    static void modL(byte[] r, int rOff, long[] x/*[64]*/)
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

    static void reduce(byte[] r)
    {
        long[] x = new long[64];
        for (int i=0;i < 64; i++) x[i] = 0xff & r[i];
        for (int i=0;i < 64;++i)r[i] = 0;
        modL(r, 0, x);
    }

    static int crypto_sign(byte[] sm, byte[] m,int n,byte[] sk)
    {
        byte[] d = new byte[64],h = new byte[64],r = new byte[64];
        long[] x = new long[64];
        long[][] /*gf*/ p/*[4]*/ = new long[4][GF_LEN];

        crypto_hash(d, sk, 0, 32);
        d[0] &= 248;
        d[31] &= 127;
        d[31] |= 64;

//        smlen[0] = n+64;
        for (int i=0;i < n;++i)sm[64 + i] = m[i];
        for (int i=0;i < 32;++i)sm[32 + i] = d[32 + i];

        crypto_hash(r, sm, 32, n + 32);
        reduce(r);
        scalarbase(p, r, 0);
        pack(sm, p);

        for (int i=0;i < 32;++i)sm[i+32] = sk[i+32];
        crypto_hash(h, sm, 0, n + 64);
        reduce(h);

        for (int i=0;i < 64;++i)x[i] = 0;
        for (int i=0;i < 32; ++i) x[i] = 0xff & r[i];
        for (int i=0;i < 32; ++i) for(int j=0; j < 32; ++j) x[i+j] += h[i] * 0xff & d[j];
        modL(sm, 32,x);

        return 0;
    }

    static int unpackneg(long[][] /*gf*/ r/*[4]*/,byte[] p/*[32]*/)
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

    static int crypto_sign_open(byte[] m, byte[] sm, int n, byte[] pk)
    {
        int i;
        byte[] t = new byte[32],h = new byte[64];
        long[][] /*gf*/ p = new long[4][GF_LEN],q = new long[4][GF_LEN];

//        mlen[0] = -1;
        if (n < 64) return -1;

        if (unpackneg(q,pk) != 0) return -1;

        for (i=0;i < n;++i)m[i] = sm[i];
        for (i=0;i < 32;++i)m[i+32] = pk[i];
        crypto_hash(h, m, 0, n);
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

        for (i=0;i < n;++i)m[i] = sm[i + 64];
//        mlen[0] = n;
        return 0;
    }

    private static Random prng = new Random(0); // only used for testing, so make deterministic

    private static void randombytes(byte[] b, int len) {
        byte[] r = new byte[len];
        prng.nextBytes(r);
        System.arraycopy(r, 0, b, 0, len);
    }
}
