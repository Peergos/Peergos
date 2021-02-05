package peergos.client;

import jsinterop.annotations.*;

import java.util.*;
import java.util.stream.Collectors;

/** Utility methods to handle conversion between types necessary for Javascript interop
 *
 */
public class JsUtil {

    @JsMethod
    public static <T> List<T> asList(T[] array) {
        return Arrays.asList(array);
    }

    @JsMethod
    public static <T> Set<T> asSet(T[] array) {
        return Arrays.asList(array).stream().collect(Collectors.toSet());
    }

}
