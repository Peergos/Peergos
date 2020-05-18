package peergos.shared.util;

public class LongUtil {

    public static long intsToLong(int high, int low) {
        return (low & 0xFFFFFFFFL) + ((high & 0xFFFFFFFFL) << 32);
    }
}
