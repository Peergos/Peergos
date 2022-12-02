package peergos.shared.util;

import jsinterop.annotations.*;

public class LongUtil {

    public static long intsToLong(int high, int low) {
        return (low & 0xFFFFFFFFL) + ((high & 0xFFFFFFFFL) << 32);
    }

    @JsMethod
    public static Long box(long in) {
        return Long.valueOf(in);
    }
}
