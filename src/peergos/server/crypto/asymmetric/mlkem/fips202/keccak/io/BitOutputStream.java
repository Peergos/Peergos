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

import java.io.OutputStream;

public abstract class BitOutputStream extends OutputStream {

	@Override
	public abstract void close();

	@Override
	public void write(byte[] b, int off, int len) {
		writeBits(b, ((long) (off))<<3, ((long)len)<<3);
	}

	@Override
	public void write(byte[] b) {
		write(b, 0, b.length);
	}
	
	@Override
	public void write(int b) {
		writeBits(new byte[] { (byte) b }, 0, 8);
	}

	public abstract void writeBits(byte[] arg, long bitOff, long bitLen);
	
}
