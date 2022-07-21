package peergos.server.tests;
import org.junit.Assert;
import org.junit.Test;
import peergos.server.net.*;
import peergos.shared.util.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class FileHandlerTests {
    static final Path TEST_ROOT = PathUtil.get("test", "resources", "static_handler");
    @Test
    public  void  test_read() throws IOException {
        FileHandler fileHandler = new FileHandler(new CspHost("http://", "localhost"),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), TEST_ROOT, false, false, Optional.empty());
        for (String path : Arrays.asList("something.txt", "/something.txt")) {
            StaticHandler.Asset asset = fileHandler.getAsset(path);
            Assert.assertEquals(new String(asset.data), "The thing!");
        }

        StaticHandler.Asset hello = fileHandler.getAsset("test/hello.txt");
        Assert.assertEquals(new String(hello.data), "Hello, Peergos!");
    }
}
