package peergos.server.crypto.asymmetric.mlkem.fips203.hash;

public enum XOFParameterSet {

    SHA3_224(
            "SHA3-224",
            224*2,
            224/8,
            (byte) 2L,
            2
    ),
    SHA3_256(
            "SHA3-256",
            256*2,
            256/8,
            (byte) 2L,
            2
    ),
    SHA3_384(
            "SHA3-384",
            384*2,
            384/8,
            (byte) 2L,
            2
    ),
    SHAKE128(
            "SHAKE128",
            256,
            -1,
            (byte) 0xf,
            4
    ),
    SHAKE256(
            "SHAKE256",
            512,
            -1,
            (byte) 0xf,
            4
    );

    private final String algorithm;
    private final int capacityInBits;
    private final int digestLength;
    private final byte domainPadding;
    private final int domainPaddingBitLength;

    XOFParameterSet(String algorithm, int capacityInBits, int digestLength, byte domainPadding, int domainPaddingBitLength) {
        this.algorithm = algorithm;
        this.capacityInBits = capacityInBits;
        this.digestLength = digestLength;
        this.domainPadding = domainPadding;
        this.domainPaddingBitLength = domainPaddingBitLength;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public int getCapacityInBits() {
        return capacityInBits;
    }

    public int getDigestLength() {
        return digestLength;
    }

    public byte getDomainPadding() {
        return domainPadding;
    }

    public int getDomainPaddingBitLength() {
        return domainPaddingBitLength;
    }
}
