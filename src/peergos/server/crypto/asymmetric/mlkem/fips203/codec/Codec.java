package peergos.server.crypto.asymmetric.mlkem.fips203.codec;

public interface Codec {

    byte[] byteEncode(int d, int[] f);

    int[] compress(int d, int[] x);

    int[] byteDecode(int d, byte[] f);

    int[] decompress(int d, int[] y);

}
