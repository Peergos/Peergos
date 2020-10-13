package peergos.client;

import jsinterop.annotations.*;

import java.util.*;

/** Utility methods to handle conversion between types necessary for Javascript interop
 *
 */
public class JsUtil {

    @JsMethod
    public static <T> List<T> asList(T[] array) {
        return Arrays.asList(array);
    }
}
