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

import peergos.server.crypto.asymmetric.mlkem.fips203.hash.XOFParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.core.KeccakSponge;

public class Shake256StreamCipher extends AbstractSpongeStreamCipher {

	private KeccakSponge sponge;

	@Override
	KeccakSponge getSponge() {
		if(sponge == null) {
			sponge = new KeccakSponge(XOFParameterSet.SHAKE256);
		}
		return sponge;
	}

}
