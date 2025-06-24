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

import java.security.InvalidKeyException;

import javax.crypto.SecretKey;

public abstract class RawKey implements SecretKey {

	private byte[] rawKey;
	
	public RawKey() {
		
	}
	
	public RawKey(byte[] rawKey) throws InvalidKeyException {
		setRaw(rawKey);
	}

	@Override
	public byte[] getEncoded() {
		return rawKey;
	}
	
	
	public void setRaw(byte[] rawKey) throws InvalidKeyException {
		if(rawKey == null || rawKey.length < getMinKeyLength() || rawKey.length >= getMaxKeyLength())
			throw new InvalidKeyException("Key must be between "+getMinKeyLength() + " and " + getMaxKeyLength() + " bytes. ");
		this.rawKey = rawKey;
	}

	@Override
	public String getFormat() {
		return "RAW";
	}
	
	public int getMinKeyLength() {
		return 16;
	}
	
	public int getMaxKeyLength() {
		return Integer.MAX_VALUE;
	}

}
