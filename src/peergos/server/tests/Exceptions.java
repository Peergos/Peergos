package peergos.server.tests;

import org.junit.*;

public class Exceptions {


    @Test
    public void cause() {
        try {
            rethrows();
        } catch (Throwable t) {
            Throwable cause = peergos.shared.util.Exceptions.getRootCause(t);
            if (! (cause instanceof IllegalStateException))
                throw new IllegalStateException("Fail");
        }
    }

    private static void rethrows() {
        try {
            throwsIllegal();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void throwsIllegal() {
        throw new IllegalStateException("Bob");
    }
}
