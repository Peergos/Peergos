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
import java.util.stream.*;

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
        boolean homeExists = Stream.of(mountPoint.toFile().listFiles())
                .map(f -> f.getName())
                .filter(n -> n.equals(username))
                .findAny()
                .isPresent();
        Assert.assertTrue("Correct home directory: " + homeExists, homeExists);

        Path home = mountPoint.resolve(username);

        // write a small file
        Path filename1 = home.resolve("data.txt");
        String data = "Hello Peergos!";
        FileOutputStream fout1 = new FileOutputStream(filename1.toFile());
        fout1.write(data.getBytes());
        fout1.flush();
        fout1.close();
        byte[] smallFileContents = Serialize.readFully(new FileInputStream(filename1.toFile()));
        Assert.assertTrue("Correct file contents: " + new String(smallFileContents), Arrays.equals(smallFileContents, data.getBytes()));

        // correct file size
        Assert.assertTrue("Correct file size", filename1.toFile().length() == data.getBytes().length);

        // rename a file
        Path newFileName = home.resolve("moredata.txt");
        boolean renamed = filename1.toFile().renameTo(newFileName.toFile());
        readStdout(Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "mv " + home + "/data.txt " + home + "/" + newFileName}));
        byte[] movedSmallFileContents = Serialize.readFully(new FileInputStream(newFileName.toFile()));
        Assert.assertTrue("Correct moved file contents", renamed && Arrays.equals(movedSmallFileContents, data.getBytes()));

        // mkdir
        Path directory = home.resolve("adirectory");
        boolean mkdir = directory.toFile().mkdir();
        boolean dirPresent = Stream.of(home.toFile().listFiles())
                .map(f -> f.getName())
                .filter(n -> n.equals(directory.getFileName().toString()))
                .findAny()
                .isPresent();
        Assert.assertTrue("Mkdir exists", mkdir && dirPresent);

        //move a file to a different directory (calls rename)
        Path inDir = directory.resolve(newFileName.getFileName().toString());
        boolean moved = newFileName.toFile().renameTo(inDir.toFile());
        byte[] movedToDirSmallFileContents = Serialize.readFully(new FileInputStream(inDir.toFile()));
        Assert.assertTrue("Correct file contents after move to another directory", moved && Arrays.equals(movedToDirSmallFileContents, data.getBytes()));
    }

    @Test
    public void mediumFileTest() throws IOException {
        // write a medium file
        byte[] tmp = new byte[5*1024*1024 + 256*1024];
        new Random().nextBytes(tmp);
        Path mediumFile = home.resolve("data2.txt");
        FileOutputStream fout = new FileOutputStream(mediumFile.toFile());
        fout.write(tmp);
        fout.flush();
        fout.close();
        byte[] readMediumFileContents = Serialize.readFully(new FileInputStream(mediumFile.toFile()));
        Assert.assertTrue("Correct medium size file contents", Arrays.equals(tmp, readMediumFileContents));
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
