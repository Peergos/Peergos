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
import java.nio.file.*;
import java.util.*;

public class FuseTests {
    public static int WEB_PORT = 8888;
    public static int CORE_PORT = 7777;
    public static String username = "test02";
    public static String password = username;
    public static Path mountPoint, home;
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
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));

        Args.parse(new String[]{"useIPFS", "false",
                "-port", Integer.toString(WEB_PORT),
                "-corenodePort", Integer.toString(CORE_PORT)});

        Start.local();
        UserContext userContext = ensureSignedUp(username, password);


        String mountPath = Args.getArg("mountPoint", "/tmp/peergos/tmp");

        mountPoint = Paths.get(mountPath);
        mountPoint = mountPoint.resolve(UUID.randomUUID().toString());
        mountPoint.toFile().mkdirs();
        home = mountPoint.resolve(username);

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
        Assert.assertTrue("Correct home directory: " + homeName, homeName.equals(username));

        Path home = mountPoint.resolve(username);

        // write a small file
        String data = "Hello Peergos!";
        readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "echo \"" + data + "\" > " + home + "/data.txt"}));
        String smallFileContents = readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "cat " + home + "/data.txt"}));
        Assert.assertTrue("Correct file contents: " + smallFileContents, smallFileContents.equals(data));

        // correct file size
        String fileSizePlusOne = readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "wc -c " + home + "/data.txt"})).split(" ")[0];
        Assert.assertTrue("Correct file size", fileSizePlusOne.equals(Integer.toString(data.length() + 1)));

        // rename a file
        String newFileName = "moredata.txt";
        readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "mv " + home + "/data.txt " + home + "/" + newFileName}));
        String movedSmallFileContents = readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "cat " + home + "/" + newFileName}));
        Assert.assertTrue("Correct moved file contents", movedSmallFileContents.equals(data));

        // mkdir
        String dirName = "adirectory";
        readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "mkdir " + home + "/" + dirName}));
        String dirLs = readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "ls " + home + "/"}));
        Assert.assertTrue("Mkdir exists", dirLs.contains(dirName));

        //move a file to a different directory (calls rename)
        readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "mv " + home + "/" + newFileName + " " + home + "/" + dirName + "/" + newFileName}));
        String movedToDirSmallFileContents = readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "cat " + home + "/" + dirName + "/" + newFileName}));
        Assert.assertTrue("Correct file contents after move to another directory", movedToDirSmallFileContents.equals(data));
    }

    @Test
    public void mediumFileTest() throws IOException {
        // write a medium file
        byte[] tmp = new byte[5*1024*1024]; // File size will be twice this
        new Random().nextBytes(tmp);
        String mediumFileContents = ArrayOps.bytesToHex(tmp);
        Path tmpFile = Files.createTempFile("" + System.currentTimeMillis(), "");
        FileOutputStream fout = new FileOutputStream(tmpFile.toFile());
        fout.write(mediumFileContents.getBytes());
        fout.flush();
        fout.close();
        readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "cp \""+tmpFile+"\" " + home + "/data2.txt"}));
        String readMediumFileContents = readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "cat " + home + "/data2.txt"}));
        Assert.assertTrue("Correct medium size file contents", mediumFileContents.equals(readMediumFileContents));
    }

    private static void runForAWhile() {
        for (int i=0; i < 600; i++)
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {}
    }

    @AfterClass
    public static void shutdown() {
        fuseProcess.close();
    }
}
