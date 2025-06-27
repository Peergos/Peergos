/*
 * Copyright (c) 2024 - Mimiclone, Inc.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package peergos.server.crypto.asymmetric.mlkem.fips202.keccak.core;

import java.util.Arrays;

import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.core.KeccakStateUtils.StateOp;

/**
 * Java port of the reference implementation of Keccack-1600 permuation
 * from <a href="https://github.com/gvanas/KeccakCodePackage">HERE</a>
 *
 */
public final class Keccak1600 {

	public Keccak1600()
	{
		this(256, 24);
	}

	public Keccak1600(int bitCapacity) {
		this(bitCapacity, NR_ROUNDS);
	}

	public Keccak1600(int bitCapacity, int rounds) {
		this.capacityBits = bitCapacity;
		this.rateBits = 1600-bitCapacity;
		this.rateBytes = rateBits >> 3;
		this.firstRound = NR_ROUNDS - rounds;

		clear();
	}

	byte byteOp(StateOp stateOp, int stateByteOff, byte in)
	{
		return KeccakStateUtils.byteOp(stateOp, state, stateByteOff, in);
	}

	void bytesOp(StateOp stateOp, int stateByteOff, byte[] out, int outpos, byte[] in, int inpos, int lenBytes)
	{
		KeccakStateUtils.bytesOp(stateOp, state, stateByteOff, out, outpos, in, inpos, lenBytes);
	}

	void bitsOp(StateOp stateOp, int stateBitOff, byte[] out, long outpos, byte[] in, long inpos, int lenBits)
	{
		KeccakStateUtils.bitsOp(stateOp, state, stateBitOff, out, outpos, in, inpos, lenBits);
	}

	public void validateBytes(int stateByteOff, byte[] buf, int bufByteOff, int lenBytes) {
		bytesOp(StateOp.VALIDATE, stateByteOff, null, 0, buf, bufByteOff, lenBytes);
	}

	public void wrapBytes(int stateByteOff, byte[] outBuf, int outBufOff, byte[] inBuf, int inBufOff, int lenBytes)  {
		bytesOp(StateOp.WRAP, stateByteOff, outBuf, outBufOff, inBuf, inBufOff, lenBytes);
	}

	public void unwrapBytes(int stateByteOff, byte[] outBuf, int outBufOff, byte[] inBuf, int inBufOff, int lenBytes) {
		bytesOp(StateOp.UNWRAP, stateByteOff, outBuf, outBufOff, inBuf, inBufOff, lenBytes);
	}

	public void getBytes(int stateByteOff, byte[] buf, int bufByteOff, int lenBytes) {
		bytesOp(StateOp.GET, stateByteOff, buf, bufByteOff, null, 0, lenBytes);
	}

	public void setXorByte(int stateByteOff, byte val) {
		byteOp(StateOp.XOR_IN, stateByteOff, val);
	}

	public void setXorBytes(int stateByteOff, byte[] buf, int bufByteOff, int lenBytes) {
		bytesOp(StateOp.XOR_IN, stateByteOff, null, 0, buf, bufByteOff, lenBytes);
	}

	public void zeroBytes(int stateByteOff, int lenBytes) {
		bytesOp(StateOp.ZERO, stateByteOff, null, 0, null, 0, lenBytes);
	}

	public void getBits(int stateBitOff, byte[] buf, long bufBitOff, int lenBits) {
		bitsOp(StateOp.GET, stateBitOff, buf, bufBitOff, null, 0, lenBits);
	}

	public final void setXorBits(int stateBitOff, byte[] buf, long bufBitOff, int lenBits) {
		bitsOp(StateOp.XOR_IN, stateBitOff, null, 0, buf, bufBitOff, lenBits);
	}

	public void zeroBits(int stateBitOff, int lenBits) {
		bitsOp(StateOp.ZERO, stateBitOff, null, 0, null, 0, lenBits);
	}

	public void validateBits(int stateBitOff, byte[] buf, int bufBitOff, int lenBits) {
		bitsOp(StateOp.VALIDATE, stateBitOff, null, 0, buf, bufBitOff, lenBits);
	}

	public void wrapBits(int stateBitOff, byte[] outBuf, int outBufOff, byte[] inBuf, int inBufOff, int lenBits)  {
		bitsOp(StateOp.WRAP, stateBitOff, outBuf, outBufOff, inBuf, inBufOff, lenBits);
	}

	public void unwrapBits(int stateBitOff, byte[] outBuf, int outBufOff, byte[] inBuf, int inBufOff, int lenBits) {
		bitsOp(StateOp.UNWRAP, stateBitOff, outBuf, outBufOff, inBuf, inBufOff, lenBits);
	}

	public int remainingLongs(int longOff) {
		return remainingBits(longOff << 6) >> 6;
	}

	public int remainingBytes(int byteOff) {
		return remainingBits(byteOff << 3) >> 3;
	}

	public int remainingBits(int bitOff) {
		return rateBits - bitOff;
	}

	public void pad(byte domainBits, int domainBitLength, int bitPosition)
	{
		int len = rateBits - bitPosition;

		if(len < 0 || domainBitLength>=7)
			throw new IndexOutOfBoundsException();

		// add bits for multirate padding
		domainBits |= (byte) (1 << domainBitLength);
		++domainBitLength;

		boolean multirateComplete  = false;
		// no zeros in multirate padding. add final bit.
		if(len==domainBitLength+1) {
			domainBits |= (byte) (1 << domainBitLength);
			++domainBitLength;
			multirateComplete = true;
		}

		while(domainBitLength > 0) {
			int chunk = Math.min(len, domainBitLength);
			if(chunk == 0) {
				permute();
				len = rateBits;
				bitPosition = 0;
				continue;
			}
			KeccakStateUtils.bitsOp(StateOp.XOR_IN, state, bitPosition, domainBits, chunk);

			len -= chunk;
			domainBits >>= chunk;
			domainBitLength -= chunk;
			bitPosition += chunk;
		}
		if(!multirateComplete) {
			if(len == 0) {
				permute();
			}
			KeccakStateUtils.bitOp(StateOp.XOR_IN, state, rateBits-1, true);
		}
	}

	public void pad(int padBitPosition)
	{
		int len = rateBits - padBitPosition;

		if(len < 0)
			throw new IndexOutOfBoundsException();

		if(len == 0) {
			permute();
			padBitPosition=0;
		}

		KeccakStateUtils.bitOp(StateOp.XOR_IN, state, padBitPosition, true);

		if(len == 1) {
			permute();
		}

		KeccakStateUtils.bitOp(StateOp.XOR_IN, state, rateBits-1, true);
	}

	public void permute()
	{
/*
  for (int i=firstRound; i < NR_ROUNDS; ++i) {
			theta();
			rho();
			pi();
			chi();
			iota(i);
	}
*/
		long out0 = state[0];
		long out1 = state[1];
		long out2 = state[2];
		long out3 = state[3];
		long out4 = state[4];
		long out5 = state[5];
		long out6 = state[6];
		long out7 = state[7];
		long out8 = state[8];
		long out9 = state[9];
		long out10 = state[10];
		long out11 = state[11];
		long out12 = state[12];
		long out13 = state[13];
		long out14 = state[14];
		long out15 = state[15];
		long out16 = state[16];
		long out17 = state[17];
		long out18 = state[18];
		long out19 = state[19];
		long out20 = state[20];
		long out21 = state[21];
		long out22 = state[22];
		long out23 = state[23];
		long out24 = state[24];
		for (int i=firstRound; i < NR_ROUNDS; ++i) {
		// Theta
		long c0 = out0;
		long c1 = out1;
		long c2 = out2;
		long c3 = out3;
		long c4 = out4;
		c0 ^= out5;
		c1 ^= out6;
		c2 ^= out7;
		c3 ^= out8;
		c4 ^= out9;
		c0 ^= out10;
		c1 ^= out11;
		c2 ^= out12;
		c3 ^= out13;
		c4 ^= out14;
		c0 ^= out15;
		c1 ^= out16;
		c2 ^= out17;
		c3 ^= out18;
		c4 ^= out19;
		c0 ^= out20;
		c1 ^= out21;
		c2 ^= out22;
		c3 ^= out23;
		c4 ^= out24;
		long d0 = Long.rotateLeft(c1, 1) ^ c4;
		long d1 = Long.rotateLeft(c2, 1) ^ c0;
		long d2 = Long.rotateLeft(c3, 1) ^ c1;
		long d3 = Long.rotateLeft(c4, 1) ^ c2;
		long d4 = Long.rotateLeft(c0, 1) ^ c3;
		out0 = out0 ^ d0;
		out1 = out1 ^ d1;
		out2 = out2 ^ d2;
		out3 = out3 ^ d3;
		out4 = out4 ^ d4;
		out5 = out5 ^ d0;
		out6 = out6 ^ d1;
		out7 = out7 ^ d2;
		out8 = out8 ^ d3;
		out9 = out9 ^ d4;
		out10 = out10 ^ d0;
		out11 = out11 ^ d1;
		out12 = out12 ^ d2;
		out13 = out13 ^ d3;
		out14 = out14 ^ d4;
		out15 = out15 ^ d0;
		out16 = out16 ^ d1;
		out17 = out17 ^ d2;
		out18 = out18 ^ d3;
		out19 = out19 ^ d4;
		out20 = out20 ^ d0;
		out21 = out21 ^ d1;
		out22 = out22 ^ d2;
		out23 = out23 ^ d3;
		out24 = out24 ^ d4;
		// RHO AND PI
		long piOut0 = out0;
		long piOut16 = Long.rotateLeft(out5, 36);
		long piOut7 = Long.rotateLeft(out10, 3);
		long piOut23 = Long.rotateLeft(out15, 41);
		long piOut14 = Long.rotateLeft(out20, 18);
		long piOut10 = Long.rotateLeft(out1, 1);
		long piOut1 = Long.rotateLeft(out6, 44);
		long piOut17 = Long.rotateLeft(out11, 10);
		long piOut8 = Long.rotateLeft(out16, 45);
		long piOut24 = Long.rotateLeft(out21, 2);
		long piOut20 = Long.rotateLeft(out2, 62);
		long piOut11 = Long.rotateLeft(out7, 6);
		long piOut2 = Long.rotateLeft(out12, 43);
		long piOut18 = Long.rotateLeft(out17, 15);
		long piOut9 = Long.rotateLeft(out22, 61);
		long piOut5 = Long.rotateLeft(out3, 28);
		long piOut21 = Long.rotateLeft(out8, 55);
		long piOut12 = Long.rotateLeft(out13, 25);
		long piOut3 = Long.rotateLeft(out18, 21);
		long piOut19 = Long.rotateLeft(out23, 56);
		long piOut15 = Long.rotateLeft(out4, 27);
		long piOut6 = Long.rotateLeft(out9, 20);
		long piOut22 = Long.rotateLeft(out14, 39);
		long piOut13 = Long.rotateLeft(out19, 8);
		long piOut4 = Long.rotateLeft(out24, 14);
		// CHI
		out0 = piOut0 ^ ((~piOut1) & piOut2);
		out1 = piOut1 ^ ((~piOut2) & piOut3);
		out2 = piOut2 ^ ((~piOut3) & piOut4);
		out3 = piOut3 ^ ((~piOut4) & piOut0);
		out4 = piOut4 ^ ((~piOut0) & piOut1);
		out5 = piOut5 ^ ((~piOut6) & piOut7);
		out6 = piOut6 ^ ((~piOut7) & piOut8);
		out7 = piOut7 ^ ((~piOut8) & piOut9);
		out8 = piOut8 ^ ((~piOut9) & piOut5);
		out9 = piOut9 ^ ((~piOut5) & piOut6);
		out10 = piOut10 ^ ((~piOut11) & piOut12);
		out11 = piOut11 ^ ((~piOut12) & piOut13);
		out12 = piOut12 ^ ((~piOut13) & piOut14);
		out13 = piOut13 ^ ((~piOut14) & piOut10);
		out14 = piOut14 ^ ((~piOut10) & piOut11);
		out15 = piOut15 ^ ((~piOut16) & piOut17);
		out16 = piOut16 ^ ((~piOut17) & piOut18);
		out17 = piOut17 ^ ((~piOut18) & piOut19);
		out18 = piOut18 ^ ((~piOut19) & piOut15);
		out19 = piOut19 ^ ((~piOut15) & piOut16);
		out20 = piOut20 ^ ((~piOut21) & piOut22);
		out21 = piOut21 ^ ((~piOut22) & piOut23);
		out22 = piOut22 ^ ((~piOut23) & piOut24);
		out23 = piOut23 ^ ((~piOut24) & piOut20);
		out24 = piOut24 ^ ((~piOut20) & piOut21);
		// IOTA
		out0 ^= KeccackRoundConstants[i];
		}
		state[0] = out0;
		state[1] = out1;
		state[2] = out2;
		state[3] = out3;
		state[4] = out4;
		state[5] = out5;
		state[6] = out6;
		state[7] = out7;
		state[8] = out8;
		state[9] = out9;
		state[10] = out10;
		state[11] = out11;
		state[12] = out12;
		state[13] = out13;
		state[14] = out14;
		state[15] = out15;
		state[16] = out16;
		state[17] = out17;
		state[18] = out18;
		state[19] = out19;
		state[20] = out20;
		state[21] = out21;
		state[22] = out22;
		state[23] = out23;
		state[24] = out24;
	}

	public void clear() {
		Arrays.fill(state, 0l);
	}

	final static int NR_ROUNDS = 24;
	final static int NR_LANES = 25;

	long[] state = new long[NR_LANES];

	int rateBytes;
    int rateBits;
    int capacityBits;
	int firstRound;

	final static int index(int x, int y)
	{
		return (((x)%5)+5*((y)%5));
	}

	final static long rol64(long l, int offset) {
		return Long.rotateLeft(l, offset);
	}

	final static int[] KeccakRhoOffsets = new int[NR_LANES];
	final static long[] KeccackRoundConstants = new long [NR_ROUNDS];

	static {
		KeccakF1600_InitializeRoundConstants();
	    KeccakF1600_InitializeRhoOffsets();
	}

	final static void KeccakF1600_InitializeRoundConstants()
	{
		 byte[] LFSRState= new byte[] { 0x01 } ;
		 int i, j, bitPosition;

		 for(i=0; i < NR_ROUNDS; i++) {
			 KeccackRoundConstants[i] = 0;
			 for(j=0; j<7; j++) {
				 bitPosition = (1<<j)-1; //2^j-1
				 if (LFSR86540(LFSRState))
					 KeccackRoundConstants[i] ^= 1l<<bitPosition;
			 }
		 }
	}

	final static boolean LFSR86540(byte[] LFSR)
	{
	    boolean result = (LFSR[0] & 0x01) != 0;
	    if ((LFSR[0] & 0x80) != 0)
	        // Primitive polynomial over GF(2): x^8+x^6+x^5+x^4+1
	    	LFSR[0] = (byte) ((LFSR[0] << 1) ^ 0x71);
	    else
	    	LFSR[0] <<= 1;
	    return result;
	}

	final static void KeccakF1600_InitializeRhoOffsets()
	 {
		  int x, y, t, newX, newY;

		  KeccakRhoOffsets[index(0, 0)] = 0;
		  x = 1;
		  y = 0;
		  for(t=0; t<24; t++) {
			  KeccakRhoOffsets[index(x, y)] = ((t+1)*(t+2)/2) % 64;
			  newX = (0*x+1*y) % 5;
			  newY = (2*x+3*y) % 5;
			  x = newX;
			  y = newY;
		  }
	 }

	final void theta()
	{
		long[] tempC = new long[5];
		long[] tempD = new long[5];
	       int x, y;

	       for(x=0; x<5; x++) {
	           tempC[x] = 0;
	           for(y=0; y<5; y++)
	               tempC[x] ^= state[index(x, y)];
	       }
	       for(x=0; x<5; x++)
	           tempD[x] = rol64(tempC[((x+1)%5)], 1) ^ tempC[((x+4)%5)];
	       for(x=0; x<5; x++)
	           for(y=0; y<5; y++)
	               state[index(x, y)] ^= tempD[x];
	}

	final void rho()
	{
	    int x, y;

	    for(x=0; x<5; x++) for(y=0; y<5; y++)
	        state[index(x, y)] = rol64(state[index(x, y)], KeccakRhoOffsets[index(x, y)]);
	}

	final void pi()
	{
		long[] tempA = new long[25];
	    int x, y;

	    for(x=0; x<5; x++) for(y=0; y<5; y++)
	        tempA[index(x, y)] = state[index(x, y)];
	    for(x=0; x<5; x++) for(y=0; y<5; y++)
	        state[index(0*x+1*y, 2*x+3*y)] = tempA[index(x, y)];
	}

	final void chi()
	{
		long[] tempC = new long[5];
	    int x, y;

	    for(y=0; y<5; y++) {
	        for(x=0; x<5; x++)
	            tempC[x] = state[index(x, y)] ^ ((~state[index(x+1, y)]) & state[index(x+2, y)]);
	        for(x=0; x<5; x++)
	            state[index(x, y)] = tempC[x];
	    }
	}

	void iota(int indexRound)
	{
	    state[index(0, 0)] ^= KeccackRoundConstants[indexRound];
	}

	public int getRateBits() {
		return rateBits;
	}

	public int getCapacityBits() {
		return capacityBits;
	}
}
