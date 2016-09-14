package peergos.shared.util;

import jsinterop.annotations.*;

import java.util.concurrent.*;

public class FutureUtils {

    @JsMethod
    public static <T>CompletableFuture<T> incomplete() {
        return new CompletableFuture<>();
    }
}
