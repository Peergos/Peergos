package peergos.shared.io.ipfs.bases;

import java.math.*;

public class Base36 {

    public static byte[] decode(String in) {
        int zeroPrefixLength = zeroPrefixLength(in);
        if (zeroPrefixLength == in.length())
            return new byte[zeroPrefixLength];
        byte[] withoutLeadingZeroes = new BigInteger(in, 36).toByteArray();
        // toByteArray() is 2s complement, and prepends a 0 sign byte when the top bit of the magnitude is set
        int magnitudeStart = withoutLeadingZeroes.length > 1 && withoutLeadingZeroes[0] == 0 ? 1 : 0;
        byte[] res = new byte[zeroPrefixLength + withoutLeadingZeroes.length - magnitudeStart];
        System.arraycopy(withoutLeadingZeroes, magnitudeStart, res, zeroPrefixLength, withoutLeadingZeroes.length - magnitudeStart);
        return res;
    }

    public static String encode(byte[] in) {
        int zeroPrefixLength = zeroPrefixLength(in);
        StringBuilder b = new StringBuilder();
        for (int i=0; i < zeroPrefixLength; i++)
            b.append("0");
        if (zeroPrefixLength < in.length) // an all zero input is already fully encoded by the zero prefix
            b.append(new BigInteger(1, in).toString(36));
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
