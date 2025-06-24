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
package peergos.server.crypto.asymmetric.mlkem.fips202.keccak.io;

import java.io.InputStream;

public abstract class BitInputStream extends InputStream {		
	@Override
	public abstract void close();
	
	
	@Override
	public int read(byte[] b, int off, int len) {
		return (int) (readBits(b, ((long)off)<<3, ((long)len)<<3)>>3);
	}
	
	/**
	 * Transform input to output with the input stream as a key stream
	 * 
	 * @param input Input byte-array
	 * @param inputOff Input offset
	 * @param output Output byte-array
	 * @param outputOff Output offset
	 * @param len length in bytes
	 * @return Number of bytes transformed
	 */
	public int transform(byte[] input, int inputOff, byte[] output, int outputOff, int len) {
		return (int) (transformBits(input, ((long)inputOff)<<3, output, ((long)outputOff)<<3, ((long)len)<<3)>>3);
	}


	@Override
	public int read(byte[] b)  {
		return this.read(b, 0, b.length);
	}
	
	/**
	 * Transform input to output using the input stream as a key stream
	 * 
	 * @param input Input byte array
	 * @param output Output byte array
	 * 
	 * @return Number of bytes transformed
	 */
	public int transform(byte[] input, byte[] output) {
		return (int) (transformBits(input, 0L, output, 0L, ((long)input.length)<<3)>>3);
	}


	@Override
	public int read() {
		byte[] buf = new byte[1];
		readBits(buf, 0, 8);
		
		return ((int) buf[0]) & 0xff;		
	}


	public abstract long readBits(byte[] arg, long bitOff, long bitLen);
	
	/**
	 * Transform input to output using the input stream as a keystream 
	 * 
	 * @param input Input byte array
	 * @param inputOff Input offset in bits
	 * @param output Output byte array
	 * @param outputOff Output offset in bits
	 * @param bitLen Number of bits
	 * @return Number of bits transformed
	 */
	public abstract long transformBits(byte[] input, long inputOff, byte[] output, long outputOff, long bitLen);
}
