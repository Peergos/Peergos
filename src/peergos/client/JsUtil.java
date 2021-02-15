package peergos.client;

import jsinterop.annotations.*;

import java.time.LocalDateTime;
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

    @JsMethod
    public static <T> List<T> emptyList() {
        return Collections.emptyList();
    }

    @JsMethod
    public static <T> Optional<T> emptyOptional() {
        return Optional.empty();
    }

    @JsMethod
    public static <T> Optional<T> optionalOf(T of) {
        return Optional.of(of);
    }


    @JsMethod
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
