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

import java.io.FilterOutputStream;
import java.io.IOException;

import peergos.server.crypto.asymmetric.mlkem.fips203.hash.XOFParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.core.KeccakStateUtils.StateOp;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.io.BitInputStream;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.io.BitOutputStream;

public class KeccakSponge {

	Keccak1600 keccak1600;

	int domainPaddingBitLength;
	byte domainPadding;

	private int ratePos;

	SqueezeStream squeezeStream;
	AbsorbStream absorbStream;

	private final class SqueezeStream extends BitInputStream {
		private boolean closed = true;

		public SqueezeStream() {

		}

		@Override
		public void close() {
			if(!closed) {
				keccak1600.clear();
				closed = true;
				ratePos = 0;
			}
		}

		void open() {
			if(closed) {
				if(absorbStream != null)
					absorbStream.close();

				ratePos = 0;
				closed = false;
			}
		}

		@Override
		public long readBits(byte[] bits, long bitOff, long bitLen) {
			open();
			long rv = 0;
			while(bitLen > 0) {
				int remainingBits = keccak1600.remainingBits(ratePos);
				if(remainingBits <=  0) {
					keccak1600.permute();
					ratePos = 0;
					remainingBits = keccak1600.remainingBits(ratePos);
				}
				int chunk = (int) Math.min(bitLen, remainingBits);

				if((ratePos & 7)==0 && (bitOff&7)==0 && (chunk&7)==0) {
					keccak1600.getBytes(ratePos>>3, bits, (int) (bitOff>>3), chunk>>3);
				} else {
					keccak1600.getBits(ratePos, bits, bitOff, chunk);
				}

				ratePos += chunk;
				bitLen -= chunk;
				bitOff += chunk;
				rv += chunk;
			}

			return rv;
		}

		@Override
		public long transformBits(byte[] input, long inputOff, byte[] output, long outputOff, long bitLen) {
			long rv = 0;
			while(bitLen > 0) {
				int remainingBits = keccak1600.remainingBits(ratePos);
				if(remainingBits <=  0) {
					keccak1600.permute();
					ratePos = 0;
					remainingBits = keccak1600.remainingBits(ratePos);
				}
				int chunk = (int) Math.min(bitLen, remainingBits);

				if((ratePos & 7)==0 && (inputOff&7)==0 && (outputOff&7)==0 && (chunk&7)==0) {
					keccak1600.bytesOp(StateOp.XOR_TRANSFORM, ratePos>>3, output, (int) (outputOff>>3), input, (int) (inputOff>>3), chunk>>3);
				} else {
					keccak1600.bitsOp(StateOp.XOR_TRANSFORM, ratePos, output, outputOff, input, inputOff, chunk);
				}

				ratePos += chunk;
				bitLen -= chunk;
				inputOff += chunk;
				outputOff += chunk;
				rv += chunk;
			}
			return rv;
		}
	}

	private final class AbsorbStream extends BitOutputStream {
		private boolean closed = false;

		@Override
		public void close() {
			if(!closed){
				keccak1600.pad(domainPadding, domainPaddingBitLength, ratePos);
				keccak1600.permute();
				closed = true;
				ratePos = 0;
			}
		}

		@Override
		public void writeBits(byte[] bits, long bitOff, long bitLen) {
			open();
			while(bitLen > 0) {
				int remainingBits = keccak1600.remainingBits(ratePos);
				if(remainingBits <=  0) {
					keccak1600.permute();
					ratePos = 0;
					remainingBits = keccak1600.remainingBits(ratePos);
				}
				int chunk = (int) Math.min(bitLen, remainingBits);

				if((ratePos & 7)==0 && (bitOff&7)==0 && (chunk&7)==0) {
					keccak1600.setXorBytes(ratePos>>3, bits, (int) (bitOff>>3), chunk>>3);
				} else {
					keccak1600.setXorBits(ratePos, bits, bitOff, chunk);
				}

				ratePos += chunk;
				bitLen -= chunk;
				bitOff += chunk;
			}
		}

		public void open() {
			if(closed) {
				if(squeezeStream != null) {
					squeezeStream.close();
				} else {
					keccak1600.clear();
					ratePos = 0;
				}
				closed = false;
			}

		}
	}

	public KeccakSponge(XOFParameterSet params) {
		this.keccak1600 = new Keccak1600(params.getCapacityInBits());
		this.domainPadding = params.getDomainPadding();
		this.domainPaddingBitLength = params.getDomainPaddingBitLength();
	}

	public KeccakSponge(int capacityInBits, byte domainPadding, int domainPaddingBitLength) {
		this.keccak1600 = new Keccak1600(capacityInBits);
		this.domainPadding = domainPadding;
		this.domainPaddingBitLength = domainPaddingBitLength;
		this.ratePos = 0;
	}

	public void reset() {
		if(absorbStream != null) {
			absorbStream.open();
		}
	}

	public BitInputStream getSqueezeStream() {
		if(squeezeStream == null) {
			squeezeStream = new SqueezeStream();
		}
		squeezeStream.open();

		return squeezeStream;
	}

	public BitOutputStream getAbsorbStream() {
		if(absorbStream == null) {
			absorbStream = new AbsorbStream();
		}
		absorbStream.open();

		return absorbStream;
	}

	public java.io.FilterOutputStream getTransformingSqueezeStream(final java.io.OutputStream target) {
		return new FilterOutputStream(target) {
			byte[] buf = new byte[4096];

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				while(len > 0) {
					int chunk = Math.min(len, buf.length);
					getSqueezeStream().transform(b, off, buf, 0, chunk);
					target.write(buf, 0, chunk);
					off += chunk;
					len -= chunk;
				}
			}

			@Override
			public void write(byte[] b) throws IOException {
				this.write(b, 0, b.length);
			}

			@Override
			public void write(int b) throws IOException {
				target.write(b ^ getSqueezeStream().read());
			}

			@Override
			public void close() throws IOException {
				buf = null;
				getSqueezeStream().close();
				super.close();
			}
		};

	}

	public byte[] getRateBits(int boff, int len)
	{
		byte[] rv = new byte[(len+ (8 - len & 7)) >> 3];
		keccak1600.getBits(boff, rv, boff, len);
		return rv;
	}

	public int getRateBits() {
		return keccak1600.getRateBits();
	}

}
