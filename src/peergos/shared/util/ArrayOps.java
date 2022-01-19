package peergos.shared.util;

import java.util.*;
import java.util.stream.*;

public class ArrayOps
{
    public static <X> List<List<X>> group(List<X> x, int maxGroupSize) {
        return IntStream.range(0, (x.size() + maxGroupSize - 1) / maxGroupSize)
                .mapToObj(i -> x.stream()
                        .skip(maxGroupSize * i)
                        .limit(maxGroupSize)
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
    }

    /*
    Due to an unfortunate bug in GWT emulation for Arrays.copyPrimitiveArray (introduced by us), It is necessary to call this version instead.
     */
    public static int[] copyOfRange(int[] original, int from, int to) {
        int length = to - from;
        if (length < 0) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        int[] copy = new int[length];
        System.arraycopy(original, from, copy, 0, Math.min(original.length - from, length));
        return copy;
    }

    public static byte[] concat(byte[] one, byte[] two)
    {
        byte[] res = new byte[one.length+two.length];
        System.arraycopy(one, 0, res, 0, one.length);
        System.arraycopy(two, 0, res, one.length, two.length);
        return res;
    }

    public static List<ByteArrayWrapper> split(byte[] data, int size) {
        if (data.length % size != 0)
            throw new IllegalStateException("Can only split an array that is multiple of split size! " + data.length + " !/ " + size);
        List<ByteArrayWrapper> res = new ArrayList<>(data.length/size);
        for (int i=0; i < data.length/size; i++)
            res.add(new ByteArrayWrapper(Arrays.copyOfRange(data, i*size, (i+1)*size)));
        return res;
    }

    public static byte[] hexToBytes(String hex)
    {
        byte[] res = new byte[hex.length()/2];
        for (int i=0; i < res.length; i++)
            res[i] = (byte) Integer.parseInt(hex.substring(2*i, 2*i+2), 16);
        return res;
    }

    private static String[] HEX_DIGITS = new String[]{
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
    private static String[] HEX = new String[256];
    static {
        for (int i=0; i < 256; i++)
            HEX[i] = HEX_DIGITS[(i >> 4) & 0xF] + HEX_DIGITS[i & 0xF];
    }

    public static String byteToHex(byte b) {
        return HEX[b & 0xFF];
    }

    public static String byteToHex(int b) {
        return HEX[b & 0xFF];
    }

    public static String bytesToHex(byte[] data)
    {
        StringBuilder s = new StringBuilder();
        for (byte b : data)
            s.append(byteToHex(b));
        return s.toString();
    }

    public static byte[] random(int length)
    {
        byte[] res = new byte[length];
        Random r = new Random();
        r.nextBytes(res);
        return res;
    }

    public static int compare(byte[] a, byte[] b)
    {
        for (int i=0; i < Math.min(a.length, b.length); i++)
            if (a[i] != b[i])
                return a[i] & 0xff - b[i] & 0xff;
            return 0;
    }

    public static boolean equalArrays(byte[] a, int aStart, int aEnd, byte[] b, int bStart, int bEnd) {
        int len = aEnd - aStart;
        if (len != bEnd - bStart)
            return false;
        for (int i=0; i < len; i++) {
            if (a[aStart + i] != b[bStart + i])
                return false;
        }
        return true;
    }
}
