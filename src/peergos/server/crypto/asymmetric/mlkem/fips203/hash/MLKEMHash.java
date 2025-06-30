package peergos.server.crypto.asymmetric.mlkem.fips203.hash;

import org.bouncycastle.jcajce.provider.digest.SHA3;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.core.KeccakSponge;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.io.BitInputStream;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.io.BitOutputStream;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.gen.KeyPairGenerationException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MLKEMHash implements Hash {

    private final ParameterSet parameterSet;

    private final MessageDigest sha3Hash256;

    private final MessageDigest sha3Hash512;

    private final KeccakSponge shake128;

    private final KeccakSponge shake256;

    public MLKEMHash(ParameterSet parameterSet, MessageDigest sha3Hash256, MessageDigest sha3Hash512, KeccakSponge shake128, KeccakSponge shake256) {
        this.parameterSet = parameterSet;
        this.sha3Hash256 = sha3Hash256;
        this.sha3Hash512 = sha3Hash512;
        this.shake128 = shake128;
        this.shake256 = shake256;
    }

    public static MLKEMHash create(ParameterSet parameterSet) {

        MessageDigest sha3Hash256;
        MessageDigest sha3Hash512;
        KeccakSponge shake128;
        KeccakSponge shake256;

        // Bootstrap SHA3-256
        try {
            sha3Hash256 = MessageDigest.getInstance("SHA3-256");
        } catch (Exception e) {
            sha3Hash256 = new SHA3.Digest256();
        }

        // Bootstrap SHA3-512
        try {
            sha3Hash512 = MessageDigest.getInstance("SHA3-512");
        } catch (Exception e) {
            sha3Hash512 = new SHA3.Digest512();
        }

        // Bootstrap SHAKE128
        shake128 = new KeccakSponge(XOFParameterSet.SHAKE128);

        // Bootstrap SHAKE256
        shake256 = new KeccakSponge(XOFParameterSet.SHAKE256);

        return new MLKEMHash(parameterSet, sha3Hash256, sha3Hash512, shake128, shake256);
    }

    @Override
    public byte[] prfEta1(byte[] s, byte b) {

        int eta = parameterSet.getEta1();

        // Init XOF
        BitOutputStream absorbStream = shake256.getAbsorbStream();
        BitInputStream squeezeStream = shake256.getSqueezeStream();

        // Absorb s and b
        absorbStream.write(s);
        absorbStream.write(new byte[] {b});

        // Squeeze the result
        byte[] digest = new byte[64 * eta];
        if (squeezeStream.read(digest) != digest.length) {
            throw new KeyPairGenerationException("PRF SHAKE256.Squeeze() operation failed");
        }

        return digest;

    }

    @Override
    public byte[] prfEta2(byte[] s, byte b) {

        int eta = parameterSet.getEta2();

        // Init XOF
        BitOutputStream absorbStream = shake256.getAbsorbStream();
        BitInputStream squeezeStream = shake256.getSqueezeStream();

        // Absorb s and b
        absorbStream.write(s);
        absorbStream.write(new byte[] {b});

        // Squeeze the result
        byte[] digest = new byte[64 * eta];
        if (squeezeStream.read(digest) != digest.length) {
            throw new KeyPairGenerationException("PRF SHAKE256.Squeeze() operation failed");
        }

        return digest;

    }

    @Override
    public byte[] gHash(byte[] c) {

        return sha3Hash512.digest(c);

    }

    @Override
    public byte[] hHash(byte[] s) {

        return sha3Hash256.digest(s);

    }

    @Override
    public byte[] jHash(byte[] s) {

        // Init XOF
        BitOutputStream absorbStream = shake256.getAbsorbStream();
        BitInputStream squeezeStream = shake256.getSqueezeStream();

        // Absorb s
        absorbStream.write(s);

        // Squeeze the result
        byte[] digest = new byte[32];
        if (squeezeStream.read(digest) != digest.length) {
            throw new KeyPairGenerationException("PRF SHAKE256.Squeeze() operation failed");
        }

        return digest;

    }
}
