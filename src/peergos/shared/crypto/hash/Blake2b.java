package peergos.shared.crypto.hash;

// From https://github.com/alphazero/Blake2b
/* !!! Doost !!! */

/*
   A Java implementation of BLAKE2B cryptographic digest algorithm.

   Joubin Mohammad Houshyar <alphazero@sensesay.net>
   bushwick, nyc
   02-14-2014

   --

   To the extent possible under law, the author(s) have dedicated all copyright
   and related and neighboring rights to this software to the public domain
   worldwide. This software is distributed without any warranty.

   You should have received a copy of the CC0 Public Domain Dedication along with
   this software. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
*/

import java.io.Serializable;
import java.util.Arrays;

import static peergos.shared.crypto.hash.Blake2b.Engine.Assert.*;
import static peergos.shared.crypto.hash.Blake2b.Engine.LittleEndian.*;


/**  */
public interface Blake2b {
    // ---------------------------------------------------------------------
    // Specification
    // ---------------------------------------------------------------------
    public interface Spec {
        /** pblock size of blake2b */
        int param_bytes 		= 64;

        /** pblock size of blake2b */
        int block_bytes 		= 128;

        /** maximum digest size */
        int max_digest_bytes 	= 64;

        /** maximum key sie */
        int max_key_bytes 		= 64;

        /** maximum salt size */
        int max_salt_bytes 		= 16;

        /** maximum personalization string size */
        int max_personalization_bytes = 16;

        /** length of h space vector array */
        int state_space_len 		= 8;

        /** max tree fanout value */
        int max_tree_fantout 		= 0xFF;

        /** max tree depth value */
        int max_tree_depth 			= 0xFF;

        /** max tree leaf length value.Note that this has uint32 semantics
         and thus 0xFFFFFFFF is used as max value limit. */
        int max_tree_leaf_length 	= 0xFFFFFFFF;

        /** max node offset value. Note that this has uint64 semantics
         and thus 0xFFFFFFFFFFFFFFFFL is used as max value limit. */
        long max_node_offset 		= 0xFFFFFFFFFFFFFFFFL;

        /** max tree inner length value */
        int max_tree_inner_length 	= 0xFF;

        /** initialization values map ref-Spec IV[i] -> slice iv[i*8:i*8+7] */
        long[] IV = {
                0x6a09e667f3bcc908L,
                0xbb67ae8584caa73bL,
                0x3c6ef372fe94f82bL,
                0xa54ff53a5f1d36f1L,
                0x510e527fade682d1L,
                0x9b05688c2b3e6c1fL,
                0x1f83d9abfb41bd6bL,
                0x5be0cd19137e2179L
        };
    }

    // ---------------------------------------------------------------------
    // API
    // ---------------------------------------------------------------------
    // TODO add ByteBuffer variants

    /**
     * A serializable / JSON-izable object usable for pausing a hash-in-process
     * which can then be resumed with the same Parameter the original digest
     * was constructed with, and fed additional bytes to conclude the hash.
     */
    public static final class ResumeHandle implements Serializable {
        /** per spec */
        public   long[]  h = new long [ 8 ];
        /** per spec */
        public   long[]  t = new long [ 2 ];
        /** per spec */
        public   long[]  f = new long [ 2 ];
        /** per spec (tree) */
        public         boolean last_node 	= false;
        /** pulled up 2b optimal */
        public   long[]  m = new long [16];
        /** pulled up 2b optimal */
        public   long[]  v = new long [16];

        /** compressor cache buffer */
        public   byte[]  buffer;
        /** compressor cache buffer offset/cached data length */
        public         int buflen;

        /** digest length from init param - copied here on init */
        public   int outlen;

        public int type;

        /**
         * Create a reconstituted Black2b digest from
         *
         * @param param
         * @return
         */
        public Blake2b resume(Param param) {
            assert this.buffer != null && this.buffer.length == Spec.block_bytes;
            assert this.h != null && this.h.length == 8
                    && this.t != null && this.t.length == 2
                    && this.f != null && this.f.length == 2
                    && this.m != null && this.m.length == 16
                    && this.v != null && this.v.length == 16 : "Data is corrupted";
            assert this.outlen == param.getDigestLength() : "Not originally initialized from this param";
            assert this.type == 1 || this.type == 2 : "Unknown type " + this.type;
            Engine.State state = new Engine.State(outlen, this.type == 1);
            System.arraycopy(this.h, 0, state.h, 0, state.h.length);
            System.arraycopy(this.t, 0, state.t, 0, state.t.length);
            System.arraycopy(this.f, 0, state.f, 0, state.f.length);
            System.arraycopy(this.m, 0, m, 0, m.length);
            System.arraycopy(this.v, 0, v, 0, v.length);
            System.arraycopy(this.buffer, 0, state.buffer, 0, state.buffer.length);
            state.buflen = buflen;
            return type == 1 ?  new Mac(param, state) : new Digest(param, state);
        }
    }

    /** */
    void update (byte[] input) ;

    /** */
    void update (byte input) ;

    /** */
    void update (byte[] input, int offset, int len) ;

    /** */
    byte[] digest () ;

    /** */
    byte[] digest (byte[] input) ;

    /** */
    void digest (byte[] output, int offset, int len) ;

    /** */
    void reset () ;

    ResumeHandle state();
    // ---------------------------------------------------------------------
    // Blake2b Message Digest
    // ---------------------------------------------------------------------

    /** Generalized Blake2b digest. */
    public static class Digest extends Engine implements Blake2b {
        private Digest (final Param p) { super (p); }
        private Digest () { super (); }
        private Digest(Param p, State state) {
            super(state, p);
        }

        public static Digest newInstance () {
            return new Digest ();
        }
        public static Digest newInstance (final int digestLength) {
            return new Digest (new Param().setDigestLength(digestLength));
        }
        public static Digest newInstance (Param p) {
            return new Digest (p);
        }
        public static Digest newInstance (Param p, State state) {
            return new Digest(p, state);
        }
    }

    // ---------------------------------------------------------------------
    // Blake2b Message Authentication Code
    // ---------------------------------------------------------------------

    /** Message Authentication Code (MAC) digest. */
    public static class Mac extends Engine implements Blake2b {
        private Mac (final Param p, State state) { super (state, p); }
        private Mac (final Param p) { super (p); }
        private Mac () { super (); }

        /** Blake2b.MAC 512 - using default Blake2b.Spec settings with given key */
        public static Mac newInstance (final byte[] key) {
            return new Mac (new Param().setKey(key));
        }
        /** Blake2b.MAC - using default Blake2b.Spec settings with given key, with given digest length */
        public static Mac newInstance (final byte[] key, final int digestLength) {
            return new Mac (new Param().setKey(key).setDigestLength(digestLength));
        }
        /** Blake2b.MAC - using the specified Parameters.
         * @param p asserted valid configured Param with key */
        public static Mac newInstance (Param p) {
            assert p != null : "Param (p) is null";
            assert p.hasKey() : "Param (p) not configured with a key";
            return new Mac (p);
        }
    }

    // ---------------------------------------------------------------------
    // Blake2b Incremental Message Digest (Tree)
    // ---------------------------------------------------------------------

    /**
     *  Note that Tree is just a convenience class; incremental hash (tree)
     *  can be done directly with the Digest class.
     *  <br>
     *  Further node, that tree does NOT accumulate the leaf hashes --
     *  you need to do that
     */
    public static class Tree {

        final int     depth;
        final int     fanout;
        final int     leaf_length;
        final int     inner_length;
        final int     digest_length;

        /**
         *
         * @param fanout
         * @param depth
         * @param leaf_length size of data input for leaf nodes.
         * @param inner_length note this is used also as digest-length for non-root nodes.
         * @param digest_length final hash out digest-length for the tree
         */
        public Tree  (
                final int     depth,
                final int     fanout,
                final int     leaf_length,
                final int     inner_length,
                final int     digest_length
        ) {
            this.fanout = fanout;
            this.depth = depth;
            this.leaf_length = leaf_length;
            this.inner_length = inner_length;
            this.digest_length = digest_length;
        }
        private Param treeParam() {
            return new Param().
                    setDepth(depth).setFanout(fanout).setLeafLength(leaf_length).setInnerLength(inner_length);
        }
        /** returns the Digest for tree node @ (depth, offset) */
        public final Digest getNode (final int depth, final int offset) {
            final Param nodeParam = treeParam().setNodeDepth(depth).setNodeOffset(offset).setDigestLength(inner_length);
            return Digest.newInstance(nodeParam);
        }
        /** returns the Digest for root node */
        public final Digest getRoot () {
            final int depth = this.depth - 1;
            final Param rootParam = treeParam().setNodeDepth(depth).setNodeOffset(0L).setDigestLength(digest_length);
            return Digest.newInstance(rootParam);
        }
    }

    // ---------------------------------------------------------------------
    // Engine
    // ---------------------------------------------------------------------
    static class Engine implements Blake2b {

        // ---------------------------------------------------------------------
        // Blake2b State(+) per reference implementation
        // ---------------------------------------------------------------------
        // REVU: address last_node TODO part of the Tree/incremental
        static final class State {
            /** per spec */
            private final   long[]  h = new long [ 8 ];
            /** per spec */
            private final   long[]  t = new long [ 2 ];
            /** per spec */
            private final   long[]  f = new long [ 2 ];
            /** per spec (tree) */
            private         boolean last_node 	= false;
            /** pulled up 2b optimal */
            private final   long[]  m = new long [16];
            /** pulled up 2b optimal */
            private final   long[]  v = new long [16];

            /** compressor cache buffer */
            private final   byte[]  buffer;
            /** compressor cache buffer offset/cached data length */
            private         int buflen;

            /** digest length from init param - copied here on init */
            private final   int outlen;

            private final int digestType;

            State(int digestLength, boolean isMac) {
                this.buffer = new byte [ Spec.block_bytes ];
                this.outlen = digestLength;
                // do not use zero, so we can detect serialization errors
                this.digestType = isMac ? 1 : 2;
            }

            public ResumeHandle toResumableForm() {
                ResumeHandle state = new ResumeHandle();
                state.h = h;
                state.t = t;
                state.f = f;
                state.last_node = last_node;
                state.m = m;
                state.v = v;
                state.buffer = buffer;
                state.buflen = buflen;
                state.outlen = outlen;
                state.type = digestType;
                return state;
            }
        }

        private State state;
        /** configuration params */
        private final   Param param;

        /** read only */
        private static byte[] zeropad = new byte [ Spec.block_bytes ];

        /** a little bit of semantics */
        interface flag {
            int last_block 	= 0;
            int last_node 	= 1;
        }
        /** to support update(byte) */
        private final	byte[] oneByte = new byte[1];

        // ---------------------------------------------------------------------
        // Ctor & Initialization
        // ---------------------------------------------------------------------

        /** Basic use constructor pending (TODO) JCA/JCE compliance */
        Engine () {
            this( new Param() );
        }

        Engine(State state, Param param) {
            assert state != null : "state is null";
            assert param != null : "param is null";
            this.state = state;
            this.param = param;
        }

        /** User provided Param for custom configurations */
        Engine (final Param param) {
            assert param != null : "param is null";
            this.param = param;
            state  = new State(param.getDigestLength(), this instanceof Mac);
            if ( param.getDepth() > Param.Default.depth ) {
                final int ndepth = param.getNodeDepth();
                final long nxoff = param.getNodeOffset();
                if (ndepth == param.getDepth() - 1) {
                    state.last_node = true;
                    assert nxoff == 0 : "root must have offset of zero";
                } else if ( nxoff == param.getFanout() - 1) {
                    this.state.last_node = true;
                }
            }

            initialize();
        }

        public ResumeHandle state() {
            return state.toResumableForm();
        }

        private void initialize () {
            // state vector h - copy values to address reset() requests
            System.arraycopy( param.initialized_H(), 0, this.state.h, 0, Spec.state_space_len);

            // if we have a key update initial block
            // Note param has zero padded key_bytes to Spec.max_key_bytes
            if(param.hasKey){
                this.update (param.key_bytes, 0, Spec.block_bytes);
            }
        }

        // ---------------------------------------------------------------------
        // interface: Blake2b API
        // ---------------------------------------------------------------------

        /** {@inheritDoc} */
        @Override final public void reset () {
            // reset cache
            this.state.buflen = 0;
            for(int i=0; i < state.buffer.length; i++){
                state.buffer[ i ] = (byte) 0;
            }

            // reset flags
            this.state.f[ 0 ] = 0L;
            this.state.f[ 1 ] = 0L;

            // reset counters
            this.state.t[ 0 ] = 0L;
            this.state.t[ 1 ] = 0L;

            // reset state vector
            // NOTE: keep as last stmt as init calls update0 for MACs.
            initialize();
        }

        /** {@inheritDoc} */
        @Override final public void update (final byte[] b, int off, int len) {
            if (b == null) {
                throw new IllegalArgumentException("input buffer (b) is null");
            }
            /* zero or more calls to compress */
            final long[] t = state.t;
            final byte[] buffer = state.buffer;
            while (len > 0) {
                if ( state.buflen == 0) {
                    /* try compressing direct from input ? */
                    while ( len > Spec.block_bytes ) {
                        t[0] += Spec.block_bytes;
                        t[1] += (t[0] < 0 && state.buflen > -t[0]) ? 1 : 0;
                        compress( b, off);
                        len -= Spec.block_bytes;
                        off += Spec.block_bytes;
                    }
                } else if ( state.buflen == Spec.block_bytes ) {
                    /* flush */
                    t[0] += Spec.block_bytes;
                    t[1] += t[0] == 0 ? 1 : 0;
                    compress( buffer, 0 );
                    state.buflen = 0;
                    continue;
                }

                // "are we there yet?"
                if( len == 0 ) return;

                final int cap = Spec.block_bytes - state.buflen;
                final int fill = len > cap ? cap : len;
                System.arraycopy( b, off, buffer, state.buflen, fill );
                state.buflen += fill;
                len -= fill;
                off += fill;
            }
        }

        /** {@inheritDoc} */
        @Override final public void update (byte b) {
            oneByte[0] = b;
            update (oneByte, 0, 1);
        }

        /** {@inheritDoc} */
        @Override final public void update(byte[] input) {
            update (input, 0, input.length);
        }

        /** {@inheritDoc} */
        @Override final public void digest(byte[] output, int off, int len) {
            // zero pad last block; set last block flags; and compress
            System.arraycopy( zeropad, 0, state.buffer, state.buflen, Spec.block_bytes - state.buflen);
            if(state.buflen > 0) {
                this.state.t[0] += state.buflen;
                this.state.t[1] += this.state.t[0] == 0 ? 1 : 0;
            }

            this.state.f[ flag.last_block ] = 0xFFFFFFFFFFFFFFFFL;
            this.state.f[ flag.last_node ] = this.state.last_node ? 0xFFFFFFFFFFFFFFFFL : 0x0L;

            // compres and write final out (truncated to len) to output
            compress( state.buffer, 0 );
            hashout( output, off, len );

            reset();
        }

        /** {@inheritDoc} */
        @Override final public byte[] digest () throws IllegalArgumentException {
            final byte[] out = new byte [state.outlen];
            digest ( out, 0, state.outlen );
            return out;
        }

        /** {@inheritDoc} */
        @Override final public byte[] digest (byte[] input) {
            update(input, 0, input.length);
            return digest();
        }

        // ---------------------------------------------------------------------
        // Internal Ops
        // ---------------------------------------------------------------------

        /**
         * write out the digest output from the 'h' registers.
         * truncate full output if necessary.
         */
        private void hashout (final byte[] out, final int offset, final int hashlen) {
            // write max number of whole longs
            final int lcnt = hashlen >>> 3;
            long v = 0;
            int i = offset;
            final long[] h = state.h;
            for (int w = 0; w < lcnt; w++) {
                v = h [ w ];
                out [ i ] = (byte) v; v >>>= 8;
                out [ i+1 ] = (byte) v; v >>>= 8;
                out [ i+2 ] = (byte) v; v >>>= 8;
                out [ i+3 ] = (byte) v; v >>>= 8;
                out [ i+4 ] = (byte) v; v >>>= 8;
                out [ i+5 ] = (byte) v; v >>>= 8;
                out [ i+6 ] = (byte) v; v >>>= 8;
                out [ i+7 ] = (byte) v;
                i+=8;
            }

            // basta?
            if( hashlen == Spec.max_digest_bytes) return;

            // write the remaining bytes of a partial long value
            v = h [ lcnt ];
            i = lcnt << 3;
            while( i < hashlen ) {
                out [ offset + i ] = (byte) v; v >>>= 8; ++i;
            }
        }

        ////////////////////////////////////////////////////////////////////////
        /// Compression Kernel /////////////////////////////////////////// BEGIN
        ////////////////////////////////////////////////////////////////////////

        /** compress Spec.block_bytes data from b, from offset */
        private void compress (final byte[] b, final int offset) {

            // set m registers
            final long[] m = state.m;
            m[ 0] = LittleEndian.readLong(b, offset);
            m[ 1] = LittleEndian.readLong(b, offset + 8);
            m[ 2] = LittleEndian.readLong(b, offset + 16);
            m[ 3] = LittleEndian.readLong(b, offset + 24);
            m[ 4] = LittleEndian.readLong(b, offset + 32);
            m[ 5] = LittleEndian.readLong(b, offset + 40);
            m[ 6] = LittleEndian.readLong(b, offset + 48);
            m[ 7] = LittleEndian.readLong(b, offset + 56);
            m[ 8] = LittleEndian.readLong(b, offset + 64);
            m[ 9] = LittleEndian.readLong(b, offset + 72);
            m[10] = LittleEndian.readLong(b, offset + 80);
            m[11] = LittleEndian.readLong(b, offset + 88);
            m[12] = LittleEndian.readLong(b, offset + 96);
            m[13] = LittleEndian.readLong(b, offset + 104);
            m[14] = LittleEndian.readLong(b, offset + 112);
            m[15] = LittleEndian.readLong(b, offset + 120);

            // set v registers
            final   long[]  v = state.v;
            final   long[]  h = state.h;
            final   long[]  t = state.t;
            final   long[]  f = state.f;
            v[ 0] = h[0];
            v[ 1] = h[1];
            v[ 2] = h[2];
            v[ 3] = h[3];
            v[ 4] = h[4];
            v[ 5] = h[5];
            v[ 6] = h[6];
            v[ 7] = h[7];
            v[ 8] =         0x6a09e667f3bcc908L;
            v[ 9] =         0xbb67ae8584caa73bL;
            v[10] =         0x3c6ef372fe94f82bL;
            v[11] =         0xa54ff53a5f1d36f1L;
            v[12] = t [0] ^ 0x510e527fade682d1L;
            v[13] = t [1] ^ 0x9b05688c2b3e6c1fL;
            v[14] = f [0] ^ 0x1f83d9abfb41bd6bL;
            v[15] = f [1] ^ 0x5be0cd19137e2179L;

            // do the rounds
            round_0(v, m);
            round_1(v, m);
            round_2(v, m);
            round_3(v, m);
            round_4(v, m);
            round_5(v, m);
            round_6(v, m);
            round_7(v, m);
            round_8(v, m);
            round_9(v, m);
            round_0(v, m); // round 10 is identical to round 0
            round_1(v, m); // round 11 is identical to round 1

            // Update state vector h
            h[0] ^= v[0] ^ v[8];
            h[1] ^= v[1] ^ v[9];
            h[2] ^= v[2] ^ v[10];
            h[3] ^= v[3] ^ v[11];
            h[4] ^= v[4] ^ v[12];
            h[5] ^= v[5] ^ v[13];
            h[6] ^= v[6] ^ v[14];
            h[7] ^= v[7] ^ v[15];

            /* kaamil */
        }
        private void round_0(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[0];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [1];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[2];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[3];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[4];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[5];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[6];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[7];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[8];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[9];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[10];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[11];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[12];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[13];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[14];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[15];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }
        private void round_1(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[14];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [10];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[4];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[8];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[9];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[15];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[13];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[6];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[1];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[12];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[0];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[2];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[11];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[7];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[5];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[3];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }
        private void round_2(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[11];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [8];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[12];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[0];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[5];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[2];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[15];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[13];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[10];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[14];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[3];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[6];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[7];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[1];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[9];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[4];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }
        private void round_3(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[7];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [9];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[3];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[1];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[13];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[12];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[11];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[14];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[2];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[6];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[5];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[10];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[4];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[0];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[15];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[8];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }
        private void round_4(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[9];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [0];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[5];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[7];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[2];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[4];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[10];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[15];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[14];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[1];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[11];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[12];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[6];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[8];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[3];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[13];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }
        private void round_5(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[2];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [12];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[6];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[10];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[0];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[11];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[8];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[3];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[4];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[13];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[7];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[5];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[15];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[14];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[1];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[9];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }
        private void round_6(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[12];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [5];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[1];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[15];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[14];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[13];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[4];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[10];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[0];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[7];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[6];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[3];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[9];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[2];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[8];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[11];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }
        private void round_7(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[13];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [11];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[7];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[14];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[12];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[1];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[3];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[9];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[5];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[0];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[15];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[4];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[8];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[6];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[2];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[10];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }
        private void round_8(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[6];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [15];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[14];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[9];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[11];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[3];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[0];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[8];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[12];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[2];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[13];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[7];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[1];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[4];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[10];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[5];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }
        private void round_9(final long[] v, final long[] m) {
            v[ 0] = v[ 0] + v[ 4] + m[10];
            v[12] ^= v[ 0];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 0] = v[ 0] + v[ 4] + m [2];
            v[12] ^= v[ 0];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[ 8] = v[ 8] + v[12];
            v[ 4] ^= v[ 8];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );

            v[ 1] = v[ 1] + v[ 5] + m[8];
            v[13] ^= v[ 1];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 1] = v[ 1] + v[ 5] + m[4];
            v[13] ^= v[ 1];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 9] = v[ 9] + v[13];
            v[ 5] ^= v[ 9];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 2] = v[ 2] + v[ 6] + m[7];
            v[14] ^= v[ 2];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 2] = v[ 2] + v[ 6] + m[6];
            v[14] ^= v[ 2];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[10] = v[10] + v[14];
            v[ 6] ^= v[10];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 3] = v[ 3] + v[ 7] + m[1];
            v[15] ^= v[ 3];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 3] = v[ 3] + v[ 7] + m[5];
            v[15] ^= v[ 3];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[11] = v[11] + v[15];
            v[ 7] ^= v[11];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 0] = v[ 0] + v[ 5] + m[15];
            v[15] ^= v[ 0];
            v[15] = ( v[15] << 32 ) | ( v[15] >>> 32 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] >>> 24 ) | ( v[ 5] << 40 );
            v[ 0] = v[ 0] + v[ 5] + m[11];
            v[15] ^= v[ 0];
            v[15] = ( v[15] >>> 16 ) | ( v[15] << 48 );
            v[10] = v[10] + v[15];
            v[ 5] ^= v[10];
            v[ 5] = ( v[ 5] << 1 ) | ( v[ 5] >>> 63 );

            v[ 1] = v[ 1] + v[ 6] + m[9];
            v[12] ^= v[ 1];
            v[12] = ( v[12] << 32 ) | ( v[12] >>> 32 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] >>> 24 ) | ( v[ 6] << 40 );
            v[ 1] = v[ 1] + v[ 6] + + m[14];
            v[12] ^= v[ 1];
            v[12] = ( v[12] >>> 16 ) | ( v[12] << 48 );
            v[11] = v[11] + v[12];
            v[ 6] ^= v[11];
            v[ 6] = ( v[ 6] << 1 ) | ( v[ 6] >>> 63 );

            v[ 2] = v[ 2] + v[ 7] + m[3];
            v[13] ^= v[ 2];
            v[13] = ( v[13] << 32 ) | ( v[13] >>> 32 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] >>> 24 ) | ( v[ 7] << 40 );
            v[ 2] = v[ 2] + v[ 7] + m[12];
            v[13] ^= v[ 2];
            v[13] = ( v[13] >>> 16 ) | ( v[13] << 48 );
            v[ 8] = v[ 8] + v[13];
            v[ 7] ^= v[ 8];
            v[ 7] = ( v[ 7] << 1 ) | ( v[ 7] >>> 63 );

            v[ 3] = v[ 3] + v[ 4] + m[13];
            v[14] ^= v[ 3];
            v[14] = ( v[14] << 32 ) | ( v[14] >>> 32 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] >>> 24 ) | ( v[ 4] << 40 );
            v[ 3] = v[ 3] + v[ 4] + m[0];
            v[14] ^= v[ 3];
            v[14] = ( v[14] >>> 16 ) | ( v[14] << 48 );
            v[ 9] = v[ 9] + v[14];
            v[ 4] ^= v[ 9];
            v[ 4] = ( v[ 4] << 1 ) | ( v[ 4] >>> 63 );
        }

        ////////////////////////////////////////////////////////////////////////
        /// Compression Kernel //////////////////////////////////////////// FINI
        ////////////////////////////////////////////////////////////////////////

        // ---------------------------------------------------------------------
        // Helper for assert error messages
        // ---------------------------------------------------------------------
        public static final class Assert {
            public final static String exclusiveUpperBound = " >= ";
            public final static String inclusiveUpperBound = " > ";
            public final static String exclusiveLowerBound = " <= ";
            public final static String inclusiveLowerBound = " < ";
            static <T extends Number> String assertFail(final String name, final T v, final String err, final T spec) {
                new Exception().printStackTrace();
                return "'" + name + "' " + v + " is" + err + spec;
            }
        }
        // ---------------------------------------------------------------------
        // Little Endian Codecs (inlined in the compressor)
        /*
         * impl note: these are not library funcs and used in hot loops, so no
         * null or bounds checks are performed. For our purposes, this is OK.
         */
        // ---------------------------------------------------------------------

        public static class LittleEndian {
            private static final byte[] hex_digits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
            private static final byte[] HEX_digits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
            /** @return hex rep of byte (lower case). */
            static public String toHexStr (final byte[] b) {
                return toHexStr (b, false); // because String class is slower.
            }
            static public String toHexStr (final byte[] b, boolean upperCase) {
                final int len = b.length;
                final byte[] digits = new byte[ len * 2 ];
                final byte[] hex_rep = upperCase ? HEX_digits : hex_digits ;
                for (int i = 0; i < len; i++) {
                    digits [ i*2   ] = hex_rep [ (byte) (b[i] >> 4 & 0x0F)  ];
                    digits [ i*2+1 ] = hex_rep [ (byte) (b[i]      & 0x0F) ];
                }
                return new String(digits);
            }
            public static int readInt (final byte[] b, final int off) {
                int v0 = ((int)b [ off ] & 0xFF );
                v0 |= ((int)b [ off + 1 ] & 0xFF ) <<  8;
                v0 |= ((int)b [ off + 2 ] & 0xFF ) << 16;
                return v0 | ((int)b [ off + 3 ]  ) << 24;
            }
            /** Little endian - byte[] to long */
            public static long readLong (final byte[] b, final int off) {
                long v0 = ((long)b [ off ] & 0xFF );
                v0 |= ((long)b [ off + 1 ] & 0xFF ) <<  8;
                v0 |= ((long)b [ off + 2 ] & 0xFF ) << 16;
                v0 |= ((long)b [ off + 3 ] & 0xFF ) << 24;
                v0 |= ((long)b [ off + 4 ] & 0xFF ) << 32;
                v0 |= ((long)b [ off + 5 ] & 0xFF ) << 40;
                v0 |= ((long)b [ off + 6 ] & 0xFF ) << 48;
                return v0 | ((long)b [ off + 7 ] )  << 56;
            }
            /** Little endian - long to byte[] */
            public static void writeLong (long v, final byte[] b, final int off) {
                b [ off ]     = (byte) v; v >>>= 8;
                b [ off + 1 ] = (byte) v; v >>>= 8;
                b [ off + 2 ] = (byte) v; v >>>= 8;
                b [ off + 3 ] = (byte) v; v >>>= 8;
                b [ off + 4 ] = (byte) v; v >>>= 8;
                b [ off + 5 ] = (byte) v; v >>>= 8;
                b [ off + 6 ] = (byte) v; v >>>= 8;
                b [ off + 7 ] = (byte) v;
            }
            /** Little endian - int to byte[] */
            public static void writeInt (int v, final byte[] b, final int off) {
                b [ off ]     = (byte) v; v >>>= 8;
                b [ off + 1 ] = (byte) v; v >>>= 8;
                b [ off + 2 ] = (byte) v; v >>>= 8;
                b [ off + 3 ] = (byte) v;
            }
        }
    }
    // ---------------------------------------------------------------------
    // digest parameter (block)
    // ---------------------------------------------------------------------
    /** Blake2b configuration parameters block per spec */
    // REVU: need to review a revert back to non-lazy impl TODO: do & bench
    public static class Param {
        interface Xoff {
            int digest_length   = 0;
            int key_length      = 1;
            int fanout          = 2;
            int depth           = 3;
            int leaf_length     = 4;
            int node_offset     = 8;
            int node_depth      = 16;
            int inner_length    = 17;
            int reserved        = 18;
            int salt            = 32;
            int personal        = 48;
        }
        public interface Default {
            byte    digest_length   = Spec.max_digest_bytes;
            byte    key_length      = 0;
            byte    fanout          = 1;
            byte    depth           = 1;
            int     leaf_length     = 0;
            long    node_offset     = 0;
            byte    node_depth      = 0;
            byte    inner_length    = 0;
        }
        /** default bytes of Blake2b parameter block */
        final static byte[] default_bytes = new byte[ Spec.param_bytes ];
        /** initialize default_bytes */
        static {
            default_bytes [ Xoff.digest_length ] = Default.digest_length;
            default_bytes [ Xoff.key_length ] = Default.key_length;
            default_bytes [ Xoff.fanout ] = Default.fanout;
            default_bytes [ Xoff.depth ] = Default.depth;
            /* def. leaf_length is 0 fill and already set by new byte[] */
            /* def. node_offset is 0 fill and already set by new byte[] */
            default_bytes [ Xoff.node_depth ] = Default.node_depth;
            default_bytes [ Xoff.inner_length] = Default.inner_length;
            /* def. salt is 0 fill and already set by new byte[] */
            /* def. personal is 0 fill and already set by new byte[] */
        }
        /** default Blake2b h vector */
        final static long[] default_h = new long [ Spec.state_space_len ];
        static {
            default_h [0] = readLong( default_bytes, 0  );
            default_h [1] = readLong( default_bytes, 8  );
            default_h [2] = readLong( default_bytes, 16 );
            default_h [3] = readLong( default_bytes, 24 );
            default_h [4] = readLong( default_bytes, 32 );
            default_h [5] = readLong( default_bytes, 40 );
            default_h [6] = readLong( default_bytes, 48 );
            default_h [7] = readLong( default_bytes, 56 );

            default_h [0] ^= Spec.IV [0];
            default_h [1] ^= Spec.IV [1];
            default_h [2] ^= Spec.IV [2];
            default_h [3] ^= Spec.IV [3];
            default_h [4] ^= Spec.IV [4];
            default_h [5] ^= Spec.IV [5];
            default_h [6] ^= Spec.IV [6];
            default_h [7] ^= Spec.IV [7];
        }

        /** */
        private boolean hasKey = false;
        /** not sure how to make this secure - TODO */
        private byte[] key_bytes = null;
        /** */
        private byte[] bytes = null;
        /** */
        private  final long[] h = new long [ Spec.state_space_len ];

        /** */
        public Param() {
            System.arraycopy( default_h, 0, h, 0, Spec.state_space_len );
        }
        /** */
        public long[] initialized_H () {
            return h;
        }
        /** package only - copy returned - do not use in functional loops */
        public byte[] getBytes() {
            lazyInitBytes();
            byte[] copy = new byte[ bytes.length ];
            System.arraycopy( bytes, 0, copy, 0, bytes.length );
            return copy;
        }

        final byte getByteParam (final int xoffset) {
            byte[] _bytes = bytes;
            if(_bytes == null) _bytes = Param.default_bytes;
            return _bytes[ xoffset];
        }
        final int getIntParam (final int xoffset) {
            byte[] _bytes = bytes;
            if(_bytes == null) _bytes = Param.default_bytes;
            return readInt ( _bytes, xoffset);
        }
        final long getLongParam (final int xoffset) {
            byte[] _bytes = bytes;
            if(_bytes == null) _bytes = Param.default_bytes;
            return readLong ( _bytes, xoffset);
        }
        // TODO same for tree params depth, fanout, inner, node-depth, node-offset
        public final int getDigestLength() {
            return (int) getByteParam ( Xoff.digest_length );
        }
        public final int getKeyLength() {
            return (int) getByteParam ( Xoff.key_length );
        }
        public final int getFanout() {
            return (int) getByteParam ( Xoff.fanout );
        }
        public final int getDepth() {
            return (int) getByteParam ( Xoff.depth );
        }
        public final int getLeafLength() {
            return getIntParam ( Xoff.leaf_length );
        }
        public final long getNodeOffset() {
            return getLongParam ( Xoff.node_offset );
        }
        public final int getNodeDepth() {
            return (int) getByteParam ( Xoff.node_depth );
        }
        public final int getInnerLength() {
            return (int) getByteParam ( Xoff.inner_length );
        }

        public final boolean hasKey() { return this.hasKey; }

        public Param clone() {
            final Param clone = new Param();
            System.arraycopy(this.h, 0, clone.h, 0, h.length);
            clone.lazyInitBytes();
            System.arraycopy(this.bytes, 0, clone.bytes, 0, this.bytes.length);

            if(this.hasKey){
                clone.hasKey = this.hasKey;
                clone.key_bytes = new byte [Spec.max_key_bytes * 2];
                System.arraycopy(this.key_bytes, 0, clone.key_bytes, 0, this.key_bytes.length);
            }
            return clone;
        }
        ////////////////////////////////////////////////////////////////////////
        /// lazy setters - write directly to the bytes image of param block ////
        ////////////////////////////////////////////////////////////////////////
        final void lazyInitBytes () {
            if( bytes == null ) {
                bytes = new byte [ Spec.param_bytes ];
                System.arraycopy(Param.default_bytes, 0, bytes, 0, Spec.param_bytes);
            }
        }
        /* 0-7 inclusive */
        public final Param setDigestLength(int len) {
            assert len > 0 : assertFail("len", len, exclusiveLowerBound, 0);
            assert len <= Spec.max_digest_bytes : assertFail("len", len, inclusiveUpperBound, Spec.max_digest_bytes);

            lazyInitBytes();
            bytes[ Xoff.digest_length ] = (byte) len;
            h[ 0 ] = readLong( bytes, 0  );
            h[ 0 ] ^= Spec.IV [ 0 ];
            return this;
        }
        public final Param setKey (final byte[] key) {
            assert key != null : "key is null";
            assert key.length <= Spec.max_key_bytes : assertFail("key.length", key.length, inclusiveUpperBound, Spec.max_key_bytes);

            // zeropad keybytes
            this.key_bytes = new byte [Spec.max_key_bytes * 2];
            System.arraycopy ( key, 0, this.key_bytes, 0, key.length );
            lazyInitBytes();
            bytes[ Xoff.key_length ] = (byte) key.length; // checked c ref; this is correct
            h[ 0 ] = readLong( bytes, 0  );
            h[ 0 ] ^= Spec.IV [ 0 ];
            this.hasKey  = true;
            return this;
        }
        public final Param setFanout(int fanout) {
            assert fanout > 0 : assertFail("fanout", fanout, exclusiveLowerBound, 0);

            lazyInitBytes();
            bytes[ Xoff.fanout ] = (byte) fanout;
            h[ 0 ] = readLong( bytes, 0  );
            h[ 0 ] ^= Spec.IV [ 0 ];
            return this;
        }
        public final Param setDepth(int depth) {
            assert depth > 0 : assertFail("depth", depth, exclusiveLowerBound, 0);

            lazyInitBytes();
            bytes[ Xoff.depth ] = (byte) depth;
            h[ 0 ] = readLong( bytes, 0  );
            h[ 0 ] ^= Spec.IV [ 0 ];
            return this;
        }
        public final Param setLeafLength(int leaf_length) {
            assert leaf_length >= 0 : assertFail("leaf_length", leaf_length, inclusiveLowerBound, 0);

            lazyInitBytes();
            writeInt (leaf_length, bytes, Xoff.leaf_length);
            h[ 0 ] = readLong( bytes, 0  );
            h[ 0 ] ^= Spec.IV [ 0 ];
            return this;
        }

        /* 8-15 inclusive */
        public final Param setNodeOffset(long node_offset) {
            assert node_offset >= 0 : assertFail("node_offset", node_offset, inclusiveLowerBound, 0);

            lazyInitBytes();
            writeLong(node_offset, bytes, Xoff.node_offset);
            h[ 1 ] = readLong( bytes, Xoff.node_offset );
            h[ 1 ] ^= Spec.IV [ 1 ];
            return this;
        }

        /* 16-23 inclusive */
        public final Param setNodeDepth(int node_depth) {
            assert node_depth >= 0 : assertFail("node_depth", node_depth, inclusiveLowerBound, 0);

            lazyInitBytes();
            bytes[ Xoff.node_depth ] = (byte) node_depth;
            h[ 2 ] = readLong( bytes, Xoff.node_depth );
            h[ 2 ] ^= Spec.IV [ 2 ];
            h[ 3 ] = readLong( bytes, Xoff.node_depth + 8);
            h[ 3 ] ^= Spec.IV [ 3 ];
            return this;
        }
        public final Param setInnerLength(int inner_length) {
            assert inner_length >= 0 : assertFail("inner_length", inner_length, inclusiveLowerBound, 0);

            lazyInitBytes();
            bytes[ Xoff.inner_length] = (byte) inner_length;
            h[ 2 ] = readLong( bytes, Xoff.node_depth );
            h[ 2 ] ^= Spec.IV [ 2 ];
            h[ 3 ] = readLong( bytes, Xoff.node_depth + 8);
            h[ 3 ] ^= Spec.IV [ 3 ];
            return this;
        }

        /* 24-31 masked by reserved and remain unchanged */

        /* 32-47 inclusive */
        public final Param setSalt(final byte[] salt) {
            assert salt != null : "salt is null";
            assert salt.length <= Spec.max_salt_bytes : assertFail("salt.length", salt.length, inclusiveUpperBound, Spec.max_salt_bytes);

            lazyInitBytes();
            Arrays.fill ( bytes, Xoff.salt, Xoff.salt + Spec.max_salt_bytes, (byte)0);
            System.arraycopy( salt, 0, bytes, Xoff.salt, salt.length );
            h[ 4 ] = readLong( bytes, Xoff.salt );
            h[ 4 ] ^= Spec.IV [ 4 ];
            h[ 5 ] = readLong( bytes, Xoff.salt + 8 );
            h[ 5 ] ^= Spec.IV [ 5 ];
            return this;
        }

        /* 48-63 inclusive */
        public final Param setPersonal(byte[] personal) {
            assert personal != null : "personal is null";
            assert personal.length <= Spec.max_personalization_bytes : assertFail("personal.length", personal.length, inclusiveUpperBound, Spec.max_personalization_bytes);

            lazyInitBytes();
            Arrays.fill ( bytes, Xoff.personal, Xoff.personal + Spec.max_personalization_bytes, (byte)0);
            System.arraycopy( personal, 0, bytes, Xoff.personal, personal.length );
            h[ 6 ] = readLong( bytes, Xoff.personal );
            h[ 6 ] ^= Spec.IV [ 6 ];
            h[ 7 ] = readLong( bytes, Xoff.personal + 8 );
            h[ 7 ] ^= Spec.IV [ 7 ];
            return this;
        }
        ////////////////////////////////////////////////////////////////////////
        /// lazy setters /////////////////////////////////////////////////// END
        ////////////////////////////////////////////////////////////////////////
    }
}
