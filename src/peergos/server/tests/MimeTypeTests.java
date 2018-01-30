package peergos.server.tests;

import org.junit.*;
import peergos.shared.user.fs.*;

public class MimeTypeTests {

    @Test
    public void smallTextFile() {
        String mime = MimeTypes.calculateMimeType("G'day Peergos!".getBytes());
        Assert.assertTrue(mime.equals("text/plain"));
    }
}
