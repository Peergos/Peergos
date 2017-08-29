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

    public static void main(String[] args) {
        nullPath();
        multiPath();
        emptyPath();
    }

    private static void assertTrue(boolean value, String msg) {
        if(!value) {
            throw new Error(msg);
        }
    }
    private static void multiPath() {
        Exception ex = null;
        try {
            Path multiPath = get("this", "that");
        }catch(IllegalArgumentException iae) {
            ex = iae;
        }
        assertTrue(ex.getMessage().equals(ERROR_MSG_VARARGS), "expecting:" + ERROR_MSG_VARARGS);
    }
    private static void nullPath() {
        Exception ex = null;
        try {
            String nullStr = null;
            Path nullPath = get(nullStr);
        }catch(IllegalArgumentException iae) {
            ex = iae;
        }
        assertTrue(ex.getMessage().equals(ERROR_MSG_NULL_PATH), "expecting:" + ERROR_MSG_NULL_PATH);
    }

    private static void emptyPath() {
        Exception ex = null;
        try {
            Path empty = get("");
        }catch(IllegalArgumentException iae) {
            ex = iae;
        }
        assertTrue(ex.getMessage().equals(ERROR_MSG_UNINITIALISED), "expecting:" + ERROR_MSG_UNINITIALISED);
    }
}
