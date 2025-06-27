package peergos.server.crypto.asymmetric.mlkem.fips202.keccak.core;

import java.security.MessageDigest;

import peergos.server.crypto.asymmetric.mlkem.fips203.hash.XOFParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.io.BitInputStream;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.io.BitOutputStream;

public abstract class AbstractKeccakMessageDigest extends MessageDigest {

	KeccakSponge keccakSponge;
	BitOutputStream absorbStream;
	int digestLength;

	/**
	 * Security level in bits is min(capacity/2,digestLength*8).
	 *
	 * @param params An XOFParameterSet instance with a positive digest length
	 */
	public AbstractKeccakMessageDigest(XOFParameterSet params)
	{
		super(params.getAlgorithm());
		this.keccakSponge = new KeccakSponge(params.getCapacityInBits(), params.getDomainPadding(), params.getDomainPaddingBitLength());

		this.absorbStream = keccakSponge.getAbsorbStream();
		this.digestLength = params.getDigestLength();
	}

	@Override
	protected byte[] engineDigest() {
		absorbStream.close();

		byte[] rv = new byte[digestLength];
		BitInputStream bis = keccakSponge.getSqueezeStream();
		bis.read(rv);
		bis.close();

		return rv;
	}

	@Override
	protected void engineReset() {
		keccakSponge.reset();
	}

	public void engineUpdateBits(byte[] bits, long bitOff, long bitLength)
	{
		absorbStream.writeBits(bits, bitOff, bitLength);
	}

	@Override
	protected void engineUpdate(byte input) {
		absorbStream.write(((int) input));
	}

	@Override
	protected void engineUpdate(byte[] input, int offset, int len) {
		engineUpdateBits(input, ((long) offset)<<3, ((long)len)<<3);
	}

	public byte[] getRateBits(int boff, int len)
	{
		return keccakSponge.getRateBits(boff, len);
	}

	public int getRateBits() {
		return keccakSponge.getRateBits();
	}

	@Override
	protected int engineGetDigestLength() {
		return digestLength;
	}

	public KeccakSponge getSponge() {
		return keccakSponge;
	}
}
