package peergos.client;

import jsinterop.annotations.*;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

/** Utility methods to handle conversion between types necessary for Javascript interop
 *
 */
public class JSUtil {

    @JsMethod
    public static CompletableFuture<List<FileWrapper>> getFiles(UserContext context, SharedItem[] pointersArray) {
        List<SharedItem> pointers = Arrays.asList(pointersArray);
        return context.getFiles(pointers);
    }
}
