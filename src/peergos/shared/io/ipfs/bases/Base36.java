package peergos.shared.io.ipfs.bases;

import java.math.*;

public class Base36 {

    public static byte[] decode(String in) {
        byte[] withoutLeadingZeroes = new BigInteger(in, 36).toByteArray();
        int zeroPrefixLength = zeroPrefixLength(in);
        byte[] res = new byte[zeroPrefixLength + withoutLeadingZeroes.length];
        System.arraycopy(withoutLeadingZeroes, 0, res, zeroPrefixLength, withoutLeadingZeroes.length);
        return res;
    }

    public static String encode(byte[] in) {
        String withoutLeadingZeroes = new BigInteger(1, in).toString(36);
        int zeroPrefixLength = zeroPrefixLength(in);
        StringBuilder b = new StringBuilder();
        for (int i=0; i < zeroPrefixLength; i++)
            b.append("0");
        b.append(withoutLeadingZeroes);
        return b.toString();
    }

    private static int zeroPrefixLength(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != 0) {
                return i;
            }
        }
        return bytes.length;
    }

    private static int zeroPrefixLength(String in) {
        for (int i = 0; i < in.length(); i++) {
            if (in.charAt(i) != '0') {
                return i;
            }
        }
        return in.length();
    }
}
