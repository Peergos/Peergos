package peergos.tests;

import org.junit.BeforeClass;
import org.junit.Test;
import peergos.fuse.PeergosFS;
import peergos.server.Start;
import peergos.user.UserContext;
import peergos.util.Args;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FuseTests {
    public static int WEB_PORT = 9876;
    public static int CORE_PORT = 9753;
    public static PeergosFS peergosFS;
    public static String username = "test02";
    public static String password = "password";
    public static Path mountPoint = Paths.get("tmp","mnt");

    @BeforeClass public static void init() throws IOException {
        Args.parse(new String[]{"useIPFS", "false",
                "-port", Integer.toString(WEB_PORT),
                "-corenodePort", Integer.toString(CORE_PORT)});

        Start.local();
        UserContext userContext = UserTests.ensureSignedUp(username, password);
        peergosFS = new PeergosFS(userContext);

        File mount = mountPoint.toFile();
        if (!  mount.isDirectory()  && !  mount.mkdirs())
            throw  new IllegalStateException("Could not find or create mount point " + mountPoint);
    }


    @Test public void mountTest()  {
        boolean debug = true;
        boolean blocking = true;
        String[] fuseOpts = new String[0];
        peergosFS.mount(mountPoint, blocking, debug, fuseOpts);
    }
}
