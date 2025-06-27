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

import peergos.server.crypto.asymmetric.mlkem.fips202.Constants;

public class Shake256Key extends RawKey {

	@Override
	public String getAlgorithm() {
		return Constants.SHAKE256_STREAM_CIPHER;
	}

}
