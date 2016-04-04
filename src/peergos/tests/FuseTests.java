package peergos.tests;

import org.junit.*;
import peergos.corenode.*;
import peergos.crypto.*;
import peergos.fuse.*;
import peergos.server.Start;
import peergos.user.*;
import peergos.util.*;

import java.io.*;
import java.lang.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class FuseTests {
    public static int WEB_PORT = 8888;
    public static int CORE_PORT = 7777;
    public static String username = "test02";
    public static String password = username;
    public static Path mountPoint;
    public static FuseProcess fuseProcess;

    static void setFinalStatic(Field field, Object newValue) throws Exception {
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.set(null, newValue);
    }

    public static UserContext ensureSignedUp(String username, String password) throws IOException {
        DHTClient.HTTP dht = new DHTClient.HTTP(new URL("http://localhost:"+ WEB_PORT +"/"));
        Btree.HTTP btree = new Btree.HTTP(new URL("http://localhost:"+ WEB_PORT +"/"));
        HTTPCoreNode coreNode = new HTTPCoreNode(new URL("http://localhost:"+ WEB_PORT +"/"));
        UserContext userContext = UserContext.ensureSignedUp(username, password, dht, btree, coreNode);
        return userContext;
    }

    @BeforeClass
    public static void init() throws Exception {
        // use insecure random otherwise tests take ages
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random());

        Args.parse(new String[]{"useIPFS", "false",
                "-port", Integer.toString(WEB_PORT),
                "-corenodePort", Integer.toString(CORE_PORT)});

        Start.local();
        UserContext userContext = ensureSignedUp(username, password);


        String mountPath = Args.getArg("mountPoint", "/tmp/peergos/tmp");

        mountPoint = Paths.get(mountPath);
        mountPoint = mountPoint.resolve(UUID.randomUUID().toString());
        mountPoint.toFile().mkdirs();

        System.out.println("\n\nMountpoint "+ mountPoint +"\n\n");
        PeergosFS peergosFS = new PeergosFS(userContext);
        fuseProcess = new FuseProcess(peergosFS, mountPoint);

        Runtime.getRuntime().addShutdownHook(new Thread(()  -> fuseProcess.close()));

        fuseProcess.start();
    }

    public static String readStdout(Process p) throws IOException {
        return new String(Serialize.readFully(p.getInputStream())).trim();
    }

    @Test
    public void variousTests() throws IOException {
        String homeName = readStdout(Runtime.getRuntime().exec("ls " + mountPoint));
        Assert.assertTrue("Correct home directory: "+homeName, homeName.equals(username));

        Path home = mountPoint.resolve(username);

        String data = "Hello Peergos!";
        readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "echo \""+data+"\" > " + home + "/data.txt"}));
        String res = readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "cat " + home + "/data.txt"}));
        Assert.assertTrue("Correct file contents: "+res, res.equals(data));
        System.out.println(res);
    }

    @AfterClass
    public static void shutdown() {
        fuseProcess.close();
    }
}
