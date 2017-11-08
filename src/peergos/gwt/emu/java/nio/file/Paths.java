package java.nio.file;


public class Paths {

    private static final String ERROR_MSG_NULL_PATH = "Paths.get() does not support null path";
    private static final String ERROR_MSG_VARARGS = "Paths.get() does not support varargs";
    private static final String ERROR_MSG_UNINITIALISED = "Paths.get() does not support uninitialised path string";

    public static Path get(String firstPath, String... pathString) {
        if(firstPath == null) {
            throw new IllegalArgumentException(ERROR_MSG_NULL_PATH);
        }
        if(pathString.length != 0) {
            throw new IllegalArgumentException(ERROR_MSG_VARARGS);
        }
        if(firstPath.equals("")) {
            throw new IllegalArgumentException(ERROR_MSG_UNINITIALISED);
        }
        return new Path(firstPath);
    }
}
