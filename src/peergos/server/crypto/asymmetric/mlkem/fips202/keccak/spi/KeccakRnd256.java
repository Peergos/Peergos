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
package peergos.server.crypto.asymmetric.mlkem.fips202.keccak.spi;

import java.security.SecureRandomSpi;

import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.core.DuplexRandom;

/**
 * A cryptographic random implementation providing 256-bit 
 * security suitable for generating long term keys.
 * 
 * Forgets the previous state after every call to 
 * nextBytes. 
 */
public final class KeccakRnd256 extends SecureRandomSpi {
	private final DuplexRandom dr = new DuplexRandom(509);
	
	@Override
	protected byte[] engineGenerateSeed(int len) {
		byte[] rv = new byte[len];
		
		DuplexRandom.getSeedBytes(rv, 0, len);
		
		return rv;
		
	}

	@Override
	protected void engineNextBytes(byte[] buf) {
		dr.getBytes(buf, 0, buf.length);
		dr.forget();
	}

	@Override
	protected void engineSetSeed(byte[] seed) {
		dr.seed(seed, 0, seed.length);
	}

}
