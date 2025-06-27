package peergos.server.crypto.asymmetric.mlkem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class CryptoUtils {

    public static final int[] INT_BIT_MASKS = new int[]{
            0x0, 0x1, 0x3, 0x7, 0xF, 0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    };

    public static int pow(int base, int exponent) {

        int res = 1;
        while (exponent > 0)
        {
            if ((exponent & 1) == 1)
                res = res * base;

            // Exponent must be even now
            exponent = exponent >> 1;
            base = base * base;
        }
        return res;
    }

    public static int mod(int val, int base) {
        return (val % base + base) % base;
    }

    public static long bytesToLong(ByteOrder order, byte[] bytes, int offset) {

        byte[] modBytes = new byte[] {
                bytes[offset], bytes[offset+1], bytes[offset+2], bytes[offset+3],
                bytes[offset+4], bytes[offset+5], bytes[offset+6], bytes[offset+7]
        };

        ByteBuffer buffer = ByteBuffer.wrap(modBytes);
        buffer.order(order);
        return buffer.getLong();

    }

    public static void zero(byte[] toZero) {
        Arrays.fill(toZero, (byte) 0);
    }

    public static void zero(int[] toZero) {
        Arrays.fill(toZero, (byte) 0);
    }

    public static void zero(int[][] toZero) {
        for (int[] ints: toZero) {
            Arrays.fill(ints, (byte) 0);
        }
    }

    public static void zero(int[][][] toZero) {
        for (int[][] ints : toZero) {
            for (int[] anInt : ints) {
                Arrays.fill(anInt, (byte) 0);
            }
        }
    }

}
