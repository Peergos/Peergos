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

import java.nio.ByteBuffer;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;

public abstract class AbstractCipher extends CipherSpi {
	private byte[] key;
	private byte[] nonce;
	private int mode;
	
	private void setKey(Key key) throws InvalidKeyException 
	{
		if(key == null || !(key instanceof RawKey))
			throw new InvalidKeyException();

		RawKey rawKey = (RawKey) key;
		if(rawKey.getEncoded()==null)
			throw new InvalidKeyException();
		
		this.key = Arrays.copyOf(rawKey.getEncoded(), rawKey.getEncoded().length);		
		
	}
				
	@Override
	protected void engineInit(int mode, Key key, AlgorithmParameterSpec params, SecureRandom sr)
			throws InvalidKeyException, InvalidAlgorithmParameterException {
		this.mode = mode;
		setKey(key);
		if(params != null) {
			if(!(params instanceof IvParameterSpec)) {
				throw new InvalidAlgorithmParameterException();	
			}
			IvParameterSpec spec = (IvParameterSpec) params;
			nonce = Arrays.copyOf(spec.getIV(), spec.getIV().length);						
		}			
		init();
	}

	@Override
	protected void engineInit(int mode, Key key, SecureRandom sr) throws InvalidKeyException {
		this.mode = mode;
		setKey(key);
		init();
	}
	
	public void reset() {
		this.key  = null;
		this.nonce = null;
	}

	protected byte[] getKey() {
		return key;
	}
	protected byte[] getNonce() {
		return nonce;
	}

	public byte[] update(byte[] input) {
		return engineUpdate(input, 0, input.length);
	}

	public byte[] update(byte[] input, int inputOffset, int inputLen) {
		return engineUpdate(input, inputOffset, input.length);
	}

	public int update(byte[] input, int inputOffset, int inputLen, byte[] output) throws ShortBufferException {
		return engineUpdate(input, inputOffset, input.length, output, 0);		
	}

	public int update(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) throws ShortBufferException {
		return engineUpdate(input, inputOffset, inputLen, output, outputOffset);
	}
	
	@Override
	protected int engineGetBlockSize() {
		return 0;
	}

	@Override
	protected byte[] engineGetIV() {
		if(nonce != null) {
			return Arrays.copyOf(nonce, nonce.length);			
		}
		return null;
	}
	
	@Override
	protected AlgorithmParameters engineGetParameters() {
		return null;
	}
	
	@Override
	protected int engineDoFinal(byte[] input, int inputOff, int len, byte[] output, int outputOff) throws ShortBufferException,
			IllegalBlockSizeException, BadPaddingException {
		int rv = engineUpdate(input, inputOff, len, output, outputOff);		

		return rv;
	}

	@Override
	protected byte[] engineDoFinal(byte[] input, int off, int len) throws IllegalBlockSizeException, BadPaddingException  {		
		byte[] rv = new byte[engineGetOutputSize(len)];
		
		try {
			engineDoFinal(input, off, len, rv, 0);
		} catch(ShortBufferException ex) {
			throw new RuntimeException(ex);
		}
		
		return rv;
	}


	@Override
	protected int engineGetOutputSize(int inputLen) {
		return inputLen;
	}

	@Override
	protected void engineSetMode(String mode) throws NoSuchAlgorithmException {
	}

	@Override
	protected void engineSetPadding(String padding) throws NoSuchPaddingException {
		if(padding != null && padding.length() > 0 && !padding.equals("NoPadding"))
				throw new NoSuchPaddingException();		
	}
	
	
	@Override
	protected byte[] engineUpdate(byte[] input, int offset, int len)  {
		byte[] output = new byte[len];
		try { engineUpdate(input, offset, len, output, 0); } catch(ShortBufferException ex) { throw new RuntimeException(ex); }

		return output;
	}
	
	protected abstract void init() throws InvalidKeyException;

	public Key unwrap(byte[] wrappedKey, String wrappedKeyAlgorithm, int wrappedKeyType) throws InvalidKeyException, NoSuchAlgorithmException {
		return engineUnwrap(wrappedKey, wrappedKeyAlgorithm, wrappedKeyType);
	}

	public int update(ByteBuffer input, ByteBuffer output) throws ShortBufferException {
		return engineUpdate(input, output);
	}

	public void updateAAD(byte[] src) {
		engineUpdateAAD(src, 0, src.length);		
	}

	public void updateAAD(byte[] src, int offset, int len) {
		engineUpdateAAD(src, offset, len);		
	}

	public void updateAAD(ByteBuffer src) {
		engineUpdateAAD(src);		
	}

	public byte[] wrap(Key key) throws InvalidKeyException, IllegalBlockSizeException {
		return engineWrap(key);
	}

	@Override
	protected void engineInit(int mode, Key key, AlgorithmParameters ap, SecureRandom sr)
			throws InvalidKeyException, InvalidAlgorithmParameterException {
		if(ap != null)
			throw new InvalidAlgorithmParameterException();
		engineInit(mode, key, sr);
		
	}

	public void init(int opmode, Key key, AlgorithmParameterSpec params) throws InvalidKeyException, InvalidAlgorithmParameterException {
		reset();
		engineInit(opmode, key, params, null);		
	}

	public byte[] doFinal() throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
		int len = engineGetOutputSize(0);
		byte[] rv= new byte[len];
		doFinal(rv, 0);
		return rv;
	}

	public byte[] doFinal(byte[] input) throws IllegalBlockSizeException, BadPaddingException {
		return engineDoFinal(input, 0, input.length);
	}

	public int doFinal(byte[] output, int outputOffset) throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
		return engineDoFinal(null, 0, 0, output, outputOffset);
	}

	public byte[] doFinal(byte[] input, int inputOffset, int inputLen) throws IllegalBlockSizeException, BadPaddingException {
		return engineDoFinal(input, inputOffset, inputLen);

	}

	public int doFinal(byte[] input, int inputOffset, int inputLen, byte[] output) throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
		return engineDoFinal(input, inputOffset, inputLen, output, 0);
	}

	public int doFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
		return engineDoFinal(input, inputOffset, inputLen, output, outputOffset);
	}

	public int doFinal(ByteBuffer input, ByteBuffer output) throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
		return engineDoFinal(input, output);
	}
	
	protected int getMode() {
		return mode;
	}
}
