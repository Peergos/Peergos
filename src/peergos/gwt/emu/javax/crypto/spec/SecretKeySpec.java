package javax.crypto.spec;

public class SecretKeySpec {

	private final byte[] key;
	private final String algorithm;
	public SecretKeySpec(byte[] key, String algorithm)
	{
		this.key = key;
		this.algorithm = algorithm;
	}

	public byte[] getEncoded() {
		return this.key;
	}
}
