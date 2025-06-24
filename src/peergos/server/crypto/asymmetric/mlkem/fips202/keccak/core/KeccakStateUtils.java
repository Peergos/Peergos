/*
 * Copyright (c) 2024 - Mimiclone, Inc.
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package peergos.server.crypto.asymmetric.mlkem.fips202.keccak.core;

/**
 * Contains methods to manipulate Keccak 64-bit long state using various-length primitives.
 *
 */
final class KeccakStateUtils {

	public enum StateOp {

		ZERO, GET, VALIDATE, XOR_IN, XOR_TRANSFORM, WRAP, UNWRAP;

		public boolean isIn() {
			return (this == XOR_IN || this == XOR_TRANSFORM || this == WRAP || this == UNWRAP || this == VALIDATE);
		}

		public boolean isOut() {
			return (this == GET || this == XOR_TRANSFORM || this == WRAP || this == UNWRAP);
		}

	};

	/**
	 * Perform a bitwise state operation on bits packed in a long value, of which we may be using only a part.
	 *
	 * @param stateOp Operation to perform
	 * @param state An array of {@code long} values representing the state being processed
	 * @param position An {@code int} representing the position in the state array to operate upon
	 * @param value A {@code long} representing an input value for the operation
	 * @param bitOffset An {@code int} representing the offset (from zero, in bits) within the long value
	 * @param bitLength An {@code int} representing the number of bits from the offset
	 *
	 * @return A {@code long} with the result value if the operation has one, otherwise 0L.
	 */
	public static long longOp(StateOp stateOp, long[] state, int position, long value, int bitOffset, int bitLength) {

		// Declare mask
		// Java longs are always 64-bit signed twos-compliment numbers (8 bytes, big endian)
		long mask = getMask(bitOffset, bitLength);

		// Initialize the result
		long result = 0L;
		
		// Copy the state value for the given position
		long stateValue = state[position];
		
		// Switch logic based on state operation
		switch (stateOp) {
			
		case ZERO:

			// Zero out the relevant bits in the state
			// We take the existing value and apply the mask to throw away the most significant bits we don't want.
			// We then invert this operation by performing an XOR (^) with the original state value which will result
			// in 1s only in the positions where the values are different.  This has the effect of retaining the
			// unmasked bits and turning all the masked bits to 0s, which we need to do since the unmasked bits
			// represent data in the bit stream.
			//
			// Operation result ends up in the state array, and 0L is returned to caller
			state[position] = stateValue ^ (stateValue & mask);
			break;
			
		case GET:
			
			// Retrieve the masked value (just the bits we care about)
			// Result is returned to caller and not put into the state array
			result = stateValue & mask;
			break;
			
			case XOR_TRANSFORM:
			case VALIDATE:
			
			// XOR the provided input value with the existing state value and mask off the bits we care about
			// Result is returned to caller and not put into the state array
			result = (value ^ stateValue) & mask;
			break;
			
		case XOR_IN:

			// Mask the input value first and then XOR with the existing state value
			// Operation result ends up in the state array, and 0L is returned to caller
			stateValue = stateValue ^ (value & mask);
			state[position] = stateValue;
			break;

		case UNWRAP:

			// XORs the input value with the state value and masks the result, then XORs with the state value again.
			// This has the net effect of decoding a value previously encoded with the same input.
			result = (value ^ stateValue) & mask;
			stateValue = stateValue ^ result;
			state[position] = stateValue;
			break;

		case WRAP:

			// XORs the input value with the state value and masks the result, then XORs with the masked state value.
			// This has the net effect of encoding the state value with the provided input.
			result = (value ^ stateValue) & mask;
			stateValue = stateValue ^ (value & mask);
			state[position] = stateValue;
			break;

		}

		// Return result, which may be 0L depending on the operation performed if it updated the state array
		return result;

	}

	/**
	 * Calculates a bit mask within a {@code long} for a given bit offset and length.
	 *
	 * @param bitOffset An {@code int} representing the 0-indexed start bit
	 * @param bitLength An {@code int} representing the number of bits from the offset in include
	 *
	 * @return A {@code long} value containing 1s between {@code bitOffset} and {@code bitOffset + bitLength} and 0s
	 * 			in all the other bits, which can be used to mask off other long values.
	 */
	private static long getMask(int bitOffset, int bitLength) {
		long mask;

		// Check if operation bit length is less than word size
		if(bitLength < 64) {

			// Fill the mask with 64 ones, then left shift by the bitLength of the operation.
			// This leaves us with all 1s except for the least significant bitLength bits, which will be 0s.
			// We then invert that value again, which gives us all 1s for the least significant bitLength bits.
			// This leaves us with a mask we can bitwise and (&) with another value to easily throw away bits
			// we don't care about.
			// NOTE: ~ is the bitwise complement operator which inverts all 0s and 1s.  ~0L is a shorthand for 64-bits
			// 		of 1s because the alternative is using 18446744073709551615L which is the decimal representation of
			//		the same value.
			mask = ~(~0L << bitLength);
			mask = mask << bitOffset;

		} else {

			// Mask is 64 ones (~ is the bitwise inversion operation)
			// In this case we are masking nothing and using the full size of the long value (a no-op).
			mask = ~0L;

		}
		return mask;
	}

	public static long longOp(StateOp operation, long[] state, int position, long value) {
		return longOp(operation, state, position, value, 0, 64);
	}

	/**
	 * Performs an integer operation on a specific position within a long array state.
	 *
	 * @param operation The StateOp enum representing the operation to be performed.
	 * @param state     The long array representing the current state.
	 * @param position  The position in the state where the operation should be applied.
	 * @param value     The integer value to be used in the operation.
	 * @return          The result of the operation as an integer.
	 */
	public static int intOp(StateOp operation, long[] state, int position, int value) {

		// Calculate the index in the long array
		// Each long can hold two integers, so we divide the position by 2
		int lpos = position >> 1;  // Equivalent to position / 2

		// Calculate the bit offset within the long
		// If position is even, bitoff is 0; if odd, bitoff is 32
		int bitoff = (position & 1) << 5;  // Equivalent to (position % 2) * 32

		// Perform the operation and extract the result:
		// 1. Cast the input value to long and shift it left by bitoff
		// 2. Call longOp to perform the operation on the long array
		// 3. Shift the result right by bitoff to align it
		// 4. Mask with 0xffffffffL to ensure we only get the lower 32 bits
		// 5. Cast the result back to int
		return (int) ((longOp(operation, state, lpos, ((long)value) << bitoff, bitoff, 32) >>> bitoff) & 0xffffffffL);
	}

	/**
	 * Performs a short operation on a specific position within a long array state.
	 *
	 * @param stateOp The StateOp enum representing the operation to be performed.
	 * @param state   The long array representing the current state.
	 * @param pos     The position in the state where the operation should be applied.
	 * @param val     The short value to be used in the operation.
	 * @return        The result of the operation as a short.
	 */
	public static short shortOp(StateOp stateOp, long[] state, int pos, short val) {
		// Calculate the index in the long array
		// Each long can hold four shorts, so we divide the position by 4
		int lpos = pos >> 2;  // Equivalent to pos / 4

		// Calculate the bit offset within the long
		// The offset can be 0, 16, 32, or 48 depending on which of the four shorts we're targeting
		int bitoff = (pos & 3) << 4;  // Equivalent to (pos % 4) * 16

		// Perform the operation and extract the result:
		// 1. Cast the input value to long and shift it left by bitoff
		// 2. Call longOp to perform the operation on the long array
		// 3. Shift the result right by bitoff to align it
		// 4. Mask with 0xffffL to ensure we only get the lower 16 bits
		// 5. Cast the result back to short
		return (short) ((longOp(stateOp, state, lpos, ((long)val) << bitoff, bitoff, 16) >>> bitoff) & 0xffffL);
	}

	/**
	 * Performs a byte operation on a specific position within a long array state.
	 *
	 * @param stateOp The StateOp enum representing the operation to be performed.
	 * @param state   The long array representing the current state.
	 * @param pos     The position in the state where the operation should be applied.
	 * @param val     The byte value to be used in the operation.
	 * @return        The result of the operation as a byte.
	 */
	public static byte byteOp(StateOp stateOp, long[] state, int pos, byte val) {
		// Calculate the index in the long array
		// Each long can hold eight bytes, so we divide the position by 8
		int lpos = pos >> 3;  // Equivalent to pos / 8

		// Calculate the bit offset within the long
		// The offset can be 0, 8, 16, 24, 32, 40, 48, or 56 depending on which of the eight bytes we're targeting
		int bitoff = (pos & 7) << 3;  // Equivalent to (pos % 8) * 8

		// Perform the operation and extract the result:
		// 1. Cast the input value to long and shift it left by bitoff
		// 2. Call longOp to perform the operation on the long array
		// 3. Shift the result right by bitoff to align it
		// 4. Mask with 0xffL to ensure we only get the lower 8 bits
		// 5. Cast the result back to byte
		return (byte) ((longOp(stateOp, state, lpos, ((long)val) << bitoff, bitoff, 8) >>> bitoff) & 0xffL);
	}

	/**
	 * Performs a bit operation on a specific position within a long array state.
	 *
	 * @param stateOp The StateOp enum representing the operation to be performed.
	 * @param state   The long array representing the current state.
	 * @param pos     The position in the state where the operation should be applied.
	 * @param val     The boolean value to be used in the operation.
	 * @return        The result of the operation as a boolean.
	 */
	public static boolean bitOp(StateOp stateOp, long[] state, int pos, boolean val) {
		// Calculate the index in the long array
		// Each long contains 64 bits, so we divide the position by 64
		int lpos = pos >> 6;  // Equivalent to pos / 64

		// Calculate the bit offset within the long
		// The offset can be 0 to 63, representing which of the 64 bits we're targeting
		int bitoff = pos & 63;  // Equivalent to pos % 64

		// Perform the operation and extract the result:
		// 1. Convert the boolean value to a long (1 for true, 0 for false) and shift it left by bitoff
		// 2. Call longOp to perform the operation on the long array
		// 3. Shift the result right by bitoff to align it
		// 4. Mask with 1L to isolate the least significant bit
		// 5. Compare the result with 1L to determine the boolean outcome
		return ((longOp(stateOp, state, lpos, (val ? 1L : 0L) << bitoff, bitoff, 1) >>> bitoff) & 1L) == 1L;
	}

	/**
	 * Performs operations on multiple longs within the state array.
	 *
	 * @param operation The StateOp enum representing the operation to be performed.
	 * @param state   The long array representing the current state.
	 * @param position     The starting position in the state for the operation.
	 * @param outputs     The output long array (possibly null for non-output operations).
	 * @param outputPosition  The starting position in the output array.
	 * @param inputs      The input long array (possibly null for non-input operations).
	 * @param inputPosition   The starting position in the input array.
	 * @param length     The number of longs to process.
	 * @throws KeccakStateValidationFailedException If validation fails during a VALIDATE operation.
	 */
	public static void longsOp(StateOp operation, long[] state, int position,
							   long[] outputs, int outputPosition, long[] inputs, int inputPosition, int length) {

		long invalid = 0L;
		boolean isIn = operation.isIn();   // Check if the operation requires an input
		boolean isOut = operation.isOut(); // Check if the operation produces an output

		while (length > 0) {
			long tmp = 0L;

			// If the operation requires an input, read from the input array
			if (isIn) {
				tmp = inputs[inputPosition];
				inputPosition++;
			}

			// Perform the operation on the current long
			tmp = longOp(operation, state, position, tmp);

			// If the operation produces output, write to the output array
			if (isOut) {
				outputs[outputPosition] = tmp;
				outputPosition++;
			}

			// For VALIDATE operations, accumulate any non-zero results
			if (operation == StateOp.VALIDATE) {
				invalid |= tmp;
			}

			position++;
			length--;
		}

		// If this was a VALIDATE operation and any invalid data was found, throw an exception
		if (operation == StateOp.VALIDATE && invalid != 0) {
			throw new KeccakStateValidationFailedException();
		}
	}

	/**
	 * Performs operations on multiple integers within the state array.
	 *
	 * @param operation The StateOp enum representing the operation to be performed.
	 * @param state   The long array representing the current state.
	 * @param position     The starting position in the state for the operation.
	 * @param outputs     The output int array (possibly null for non-output operations).
	 * @param outputPosition  The starting position in the output array.
	 * @param inputs      The input int array (possibly null for non-input operations).
	 * @param inputPosition   The starting position in the input array.
	 * @param length     The number of integers to process.
	 * @throws KeccakStateValidationFailedException If validation fails during a VALIDATE operation.
	 */
	public static void intsOp(StateOp operation, long[] state, int position,
							  int[] outputs, int outputPosition, int[] inputs, int inputPosition, int length) {
		long invalid = 0;
		boolean isIn = operation.isIn();   // Check if the operation requires an input
		boolean isOut = operation.isOut(); // Check if the operation produces an output

		while (length > 0) {
			// Check if we can process two integers at once (64-bit operation)
			if (length > 1 & (position & 1) == 0) {
				long mask = 0xffffffffL;
				do {
					long tmp = 0;
					if (isIn) {
						// Combine two 32-bit integers into one 64-bit long
						tmp = ((long) inputs[inputPosition]) & mask;
						++inputPosition;
						tmp |= (((long) inputs[inputPosition]) & mask) << 32;
						++inputPosition;
					}

					// Perform the operation on the 64-bit value
					tmp = longOp(operation, state, position >> 1, tmp);

					if (isOut) {
						// Split the 64-bit result back into two 32-bit integers
						outputs[outputPosition] = (int) (tmp & mask);
						++outputPosition;
						tmp >>>= 32;
						outputs[outputPosition] = (int) (tmp & mask);
						++outputPosition;
					}

					if (operation == StateOp.VALIDATE) {
						invalid |= tmp;
					}

					position += 2;
					length -= 2;
				} while (length > 1);
			} else {
				// Process a single 32-bit integer
				int tmp = 0;

				if (isIn) {
					tmp = inputs[inputPosition];
					inputPosition++;
				}

				// Perform the operation on the 32-bit value
				tmp = intOp(operation, state, position, tmp);

				if (isOut) {
					outputs[outputPosition] = tmp;
					outputPosition++;
				}

				if (operation == StateOp.VALIDATE) {
					invalid |= tmp;
				}

				position++;
				length--;
			}
		}

		// If this was a VALIDATE operation and any invalid data was found, throw an exception
		if (operation == StateOp.VALIDATE && invalid != 0) {
			throw new KeccakStateValidationFailedException();
		}
	}

	public static void shortsOp(StateOp stateOp, long[] state, int pos,
			short[] out, int outpos, short[] in, int inpos, int len)
	{
		long invalid=0;
		boolean isIn = stateOp.isIn();
		boolean isOut = stateOp.isOut();
		while(len > 0) {
			if(len > 3 && (pos&3)==0) {
				long mask = 0xffffL;
				do {
					long tmp = 0;
					if(isIn) {
						tmp = ((long) in[inpos]) & mask;
						++inpos;
						tmp |= (((long) in[inpos]) & mask)<<16;
						++inpos;
						tmp |= (((long) in[inpos]) & mask)<<32;
						++inpos;
						tmp |= (((long) in[inpos]) & mask)<<48;
						++inpos;

					}
					tmp = longOp(stateOp, state, pos>>2, tmp);
					if(isOut) {
						out[outpos] = (short) (tmp & mask);
						++outpos;
						tmp >>>= 16;
						out[outpos] = (short) (tmp & mask);
						++outpos;
						tmp >>>= 16;
						out[outpos] = (short) (tmp & mask);
						++outpos;
						tmp >>>= 16;
						out[outpos] = (short) (tmp & mask);
						++outpos;
					}
					if(stateOp == StateOp.VALIDATE) {
						invalid |= tmp;
					}
					pos += 4;
					len -= 4;
				} while(len > 3);
			} else {
				short tmp = 0;

				if(isIn) {
					tmp = in[inpos];
					inpos++;
				}
				tmp = shortOp(stateOp, state, pos, tmp);
				if(isOut) {
					out[outpos] = tmp;
					outpos++;
				}
				if(stateOp == StateOp.VALIDATE) {
					invalid |= tmp;
				}
				pos++;
				len--;
			}
		}
		if(stateOp == StateOp.VALIDATE && invalid != 0) {
			throw new KeccakStateValidationFailedException();
		}
	}

	public static void bytesOp(StateOp stateOp, long[] state, int pos,
			byte[] out, int outpos, byte[] in, int inpos, int len)
	{
		if(len > 7 && (len&7)==0 && (pos&7)==0) {
			long invalid = 0;
			boolean isIn = stateOp.isIn();
			boolean isOut = stateOp.isOut();
			long mask = 0xff;
			do {
				long tmp = 0;
				if(isIn) {
					tmp = ((long) in[inpos]) & mask;
					++inpos;
					tmp |= (((long) in[inpos]) & mask)<<8;
					++inpos;
					tmp |= (((long) in[inpos]) & mask)<<16;
					++inpos;
					tmp |= (((long) in[inpos]) & mask)<<24;
					++inpos;
					tmp |= (((long) in[inpos]) & mask)<<32;
					++inpos;
					tmp |= (((long) in[inpos]) & mask)<<40;
					++inpos;
					tmp |= (((long) in[inpos]) & mask)<<48;
					++inpos;
					tmp |= (((long) in[inpos]) & mask)<<56;
					++inpos;
				}
				tmp = longOp(stateOp, state, pos>>3, tmp);
				if(isOut) {
					out[outpos] = (byte) (tmp & mask);
					++outpos;
					tmp >>>= 8;
					out[outpos] = (byte) (tmp & mask);
					++outpos;
					tmp >>>= 8;
					out[outpos] = (byte) (tmp & mask);
					++outpos;
					tmp >>>= 8;
					out[outpos] = (byte) (tmp & mask);
					++outpos;
					tmp >>>= 8;
					out[outpos] = (byte) (tmp & mask);
					++outpos;
					tmp >>>= 8;
					out[outpos] = (byte) (tmp & mask);
					++outpos;
					tmp >>>= 8;
					out[outpos] = (byte) (tmp & mask);
					++outpos;
					tmp >>>= 8;
					out[outpos] = (byte) (tmp & mask);
					++outpos;
				}
				if(stateOp == StateOp.VALIDATE) {
					invalid |= tmp;
				}

				pos += 8;
				len -= 8;
			} while(len > 0);
			if(stateOp == StateOp.VALIDATE && invalid != 0) {
				throw new KeccakStateValidationFailedException();
			}
		} else {
			bitsOp(stateOp, state, pos<<3, out, ((long) outpos)<<3, in, ((long)inpos)<<3, len <<3);
		}
	}

	public static byte bitsOp(StateOp stateOp, long[] state, int pos, byte in, int len)
	{
		boolean isIn = stateOp.isIn();
		boolean isOut = stateOp.isOut();
		byte rv=0;

		int lpos = (pos>>6);
		int loff = (pos & 63);

		int len1 = Math.min(len, 64-loff);
		int len2 = len - len1;
		long mask1 = ((~(0xff<<len1))& 0xffL);
		long mask2 = ((~(0xff<<len2))& 0xffL);

		long tmp = 0;
		if(isIn) {
			tmp = ((long) in) & mask1;
			tmp <<= loff;
		}
		tmp = longOp(stateOp, state, lpos, tmp, loff, len1);
		if(isOut) {
			tmp >>= loff;
			rv = (byte) (tmp & mask1);
		}

		if(len2 > 0) {
			++lpos;
			loff = 0;

			if(isIn) {
				tmp = ((long) (in>>>len1)) & mask2;
			}
			tmp = longOp(stateOp, state, lpos, tmp, loff, len2);
			if(isOut) {
				rv |= ((byte) (tmp & mask2))<<len1;
			}
		}

		return rv;
	}

	public static void bitsOp(StateOp stateOp, long[] state, int pos,
			byte[] out, long outpos, byte[] in, long inpos, int len)
	{
		long invalid=0;
		boolean isIn = stateOp.isIn();
		boolean isOut = stateOp.isOut();
		while(len > 0) {
			int bitoff = pos & 63;
			int bitlen = Math.min(64 - bitoff, len);

			long tmp = 0;
			int lpos = pos >> 6;

			if(isIn) {
				tmp = setBitsInLong(in, inpos, tmp, bitoff, bitlen);
				inpos += bitlen;
			}
			tmp = longOp(stateOp, state, lpos, tmp, bitoff, bitlen);
			if(isOut) {
				setBitsFromLong(out, outpos, tmp, bitoff, bitlen);
				outpos += bitlen;
			}
			if(stateOp == StateOp.VALIDATE) {
				invalid |= tmp;
			}
			pos += bitlen;
			bitoff += bitlen;
			len -= bitlen;
		}
		if(stateOp == StateOp.VALIDATE && invalid != 0) {
			throw new KeccakStateValidationFailedException();
		}
	}

	static long setBitsInLong(byte[] src, long srcoff,  long l, int off, int len)
	{
		int shift=off;
		// clear bits in l
		long mask = ~(~0l << len);
		mask = mask << off;
		l ^= l & mask;
		while(len > 0) {
			int bitoff = (int) (srcoff & 7);
			int srcByteOff = (int) (srcoff >> 3);
			if(bitoff==0 && len >= 8) {
				do {
					// aligned byte
					long val = ((long )(src[srcByteOff])) &0xffl;

					l |= val << shift;
					shift += 8;
					len -= 8;
					srcoff += 8;
					++srcByteOff;
				} while(len >= 8);
			} else {
				int bitlen = Math.min(8 - bitoff, len);

				byte valmask = (byte) ((0xff << bitoff) & (0xff >>> (8-bitlen-bitoff)));
				long lval = ((long )(src[srcByteOff] & valmask)) & 0xffl;
				lval >>>= bitoff;

				l |= lval << shift;

				srcoff += bitlen;
				len -= bitlen;
				shift += bitlen;
			}
		}
		return l;
	}

	static void setBitsFromLong(byte[] dst, long dstoff,  long l, int off, int len)
	{
		int shift=off;
		while(len > 0) {
			int bitoff = (int) dstoff & 7;
			int dstByteOff = (int) (dstoff >> 3);

			if(bitoff==0 && len >= 8) {
				do {
					// aligned byte
					dst[dstByteOff] = (byte) ((l >>> shift) & 0xff);
					shift += 8;
					len -= 8;
					dstoff += 8;
					++dstByteOff;
				} while(len >= 8);
			} else {
				int bitlen = Math.min(8 - bitoff, len);
				byte mask = (byte) ((0xff << bitoff) & (0xff >>> (8-bitlen-bitoff)));
				byte val = dst[dstByteOff];
				long lval = (l >>> shift);

				val ^= val & mask;
				val |= (lval<<bitoff) & mask;

				dst[dstByteOff] = val;

				dstoff += bitlen;
				len -= bitlen;
				shift += bitlen;
			}
		}
	}

}
