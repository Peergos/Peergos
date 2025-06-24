package peergos.server.crypto.asymmetric.mlkem.fips203.sample;

public interface Sampler {

    int[] sampleNTT(byte[] seed, byte a, byte b);

    int[] samplePolyCBDEta1(byte[] input);

    int[] samplePolyCBDEta2(byte[] input);

}
