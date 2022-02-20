package java.nio.file;


import jsinterop.annotations.*;

import java.util.*;
import java.util.stream.*;

public class Paths {

    private static final String ERROR_MSG_NULL_PATH = "Paths.get() does not support null path";
    private static final String ERROR_MSG_VARARGS = "Paths.get() does not support varargs";
    private static final String ERROR_MSG_UNINITIALISED = "Paths.get() does not support uninitialised path string";

    @JsMethod
    public static Path get(String firstPath, String... pathString) {
        if (firstPath == null) {
            throw new IllegalArgumentException(ERROR_MSG_NULL_PATH);
        }
        if (firstPath.equals("")) {
            return new Path("");
        }
        return new Path(Stream.concat(Stream.of(firstPath), Arrays.stream(pathString)).collect(Collectors.joining("/")));
    }
}
