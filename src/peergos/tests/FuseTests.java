package peergos.tests;

import org.junit.*;
import static org.junit.Assert.*;
import static java.util.UUID.*;

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
import java.nio.file.attribute.FileTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.*;

public class FuseTests {
    public static int WEB_PORT = 8888;
    public static int CORE_PORT = 7777;
    public static String username = "test02";
    public static String password = username;
    public static Path mountPoint, home;
    public static FuseProcess fuseProcess;
    public static Random RANDOM = new Random(666);

    public static void setWebPort(int webPort) {
        WEB_PORT = webPort;
    }

    public static void setCorePort(int corePort) {
        CORE_PORT = corePort;
    }

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
        Random  random  = new Random();
//        int offset = random.nextInt(100);
        int offset = 0;
        setWebPort(8888 + offset);
        setCorePort(7777 + offset);

        System.out.println("Using web-port "+ WEB_PORT);
        System.out.flush();
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

    @Test public void  createFileTest() throws IOException  {
        Path resolve = home.resolve(UUID.randomUUID().toString());
        assertFalse("file already exists", resolve.toFile().exists());
        resolve.toFile().createNewFile();
        assertTrue("file exists after creation", resolve.toFile().exists());
    }

    @Test public void moveTest() throws IOException {
        Path initial = createRandomFile(0x1000);

        byte[] initialData = Files.readAllBytes(initial);

        Path target = initial.getParent().resolve(randomUUID().toString());
        assertFalse("target exists", target.toFile().exists());

        Files.move(initial, target);

        assertFalse("initial still exists", initial.toFile().exists());
        assertTrue("target exists", target.toFile().exists());
        byte[] targetData = Files.readAllBytes(target);

        assertTrue("target contents equal to iniital contents", Arrays.equals(initialData, targetData));
    }

    @Test public void copyFileTest() throws IOException  {
        Path initial = createRandomFile(0x1000);
        Path target = initial.getParent().resolve(randomUUID().toString());

        assertFalse("target exists", target.toFile().exists());
        Files.copy(initial, target);

        assertTrue("initial exists", initial.toFile().exists());
        assertTrue("target exists", target.toFile().exists());

        boolean contentEquals = Arrays.equals(
                Files.readAllBytes(initial),
                Files.readAllBytes(target));

        assertTrue("initial and target contents equal", contentEquals);
    }

    @Test public  void removeTest() throws IOException {
        Path path = createRandomFile();
        assertTrue("path exists", path.toFile().exists());
        Files.delete(path);
        assertFalse("path exists", path.toFile().exists());
    }

    @Test public  void truncateTest() throws IOException {
        int initialLength = 0x1000;
        Path path = createRandomFile(initialLength);

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            assertEquals("initial size", initialLength, raf.length());

            for (int pow = -1; pow < 4; pow++) {
                int newSize = (int) (Math.pow(2, pow) * initialLength);
                raf.setLength(newSize);
                assertEquals("truncated size equals", newSize, raf.length());
            }
        }
    }

    @Test public  void lastModifiedTimeTest() throws IOException {
        Path path = createRandomFile();
        FileTime lastModifiedTime = Files.getLastModifiedTime(path);
        long epochMillis = lastModifiedTime.toMillis();
        System.out.println("last modified time " + lastModifiedTime +" "+ new Date(epochMillis));

        long currentTimeMillis = System.currentTimeMillis();
        long delta = Math.abs(currentTimeMillis - epochMillis);
        int tolerance = 1000*60*3;
//        assertTrue("last modified time up to date", delta < tolerance);

        long yesterdayEpochMillis = ZonedDateTime.now().minusDays(1).toInstant().toEpochMilli();
        FileTime updatedTimestamp = FileTime.fromMillis(yesterdayEpochMillis);

        Files.setLastModifiedTime(path, updatedTimestamp);
        FileTime updatedLastModifiedTime = Files.getLastModifiedTime(path);

        assertEquals("last-modified  timestamp is set", updatedTimestamp, updatedLastModifiedTime);
    }



    @Test public void mkdirsTest() throws IOException {

        String[] stem = Stream.generate(randomUUID()::toString)
                .limit(10)
                .toArray(String[]::new);

        Path path = Paths.get(home.toString(), stem);
        assertFalse("path exists initially", path.toFile().exists());

        path.toFile().mkdirs();

        assertTrue("path is directory", path.toFile().isDirectory());
    }

    @Test public  void rmdirTest() throws IOException {
        Path path = home.resolve(randomUUID().toString());

        assertFalse("dir exists initially",
                path.toFile().exists());

        path.toFile().mkdir();

        assertTrue("dir exists after creation",
                path.toFile().exists());

        path.toFile().delete();

        assertFalse("dir exists after deletion",
                path.toFile().exists());
    }


    private Path createRandomFile() throws IOException {
        return createRandomFile(0);
    }

    private Path createRandomFile(int length) throws IOException {
        Path resolve = home.resolve(UUID.randomUUID().toString());
        resolve.toFile().createNewFile();

        if (length > 0) {
            byte[] data =  new byte[length];
            RANDOM.nextBytes(data);
            Files.write(resolve, data);
        }

        return resolve;
    }


    @Test
    public void variousTests() throws IOException {
        boolean homeExists = Stream.of(mountPoint.toFile().listFiles())
                .map(f -> f.getName())
                .filter(username::equals)
                .findAny()
                .isPresent();
        Assert.assertTrue("Correct home directory: " + homeExists, homeExists);

        Path home = mountPoint.resolve(username);

        // write a small file
        Path filename1 = home.resolve("data.txt");
        String msg = "Hello Peergos!";

        Files.write(filename1, msg.getBytes());

        byte[] smallFileContents = Files.readAllBytes(filename1);
        Assert.assertTrue("Correct file contents: " + msg, Arrays.equals(smallFileContents, msg.getBytes()));

        // rename a file
        Path newFileName = home.resolve("moredata.txt");
        Supplier<Boolean> newPathExists = () -> newFileName.toFile().exists();
        Assert.assertFalse("updated file "+  newFileName+" doesn't already exist", newPathExists.get());

        Files.move(filename1, newFileName);
        byte[] movedContents  = Files.readAllBytes(newFileName);

        Assert.assertFalse("original file "+ filename1 +" present after move", filename1.toFile().exists());

        Assert.assertTrue("updated file "+  newFileName+" exist", newPathExists.get());
        Assert.assertTrue("Correct moved file contents", Arrays.equals(movedContents, msg.getBytes()));

        // mkdir
        Path directory = home.resolve("adirectory");

        Supplier<Boolean> directoryExists = () -> directory.toFile().exists();
        Assert.assertFalse("directory "+ directory +" doesn't already exist", directoryExists.get());

        directory.toFile().mkdir();

        Assert.assertTrue("Mkdir exists", directoryExists.get());

        //move a file to a different directory (calls rename)
        Path inDir = directory.resolve(newFileName.getFileName());
        Supplier<Boolean> inDirExists = () -> inDir.toFile().exists();
        Assert.assertFalse("new file in directory "+ inDir +" doesn't already exist", inDirExists.get());

        Files.move(newFileName, inDir);

        Assert.assertTrue("new file in directory "+ inDir +" exist", inDirExists.get());

        Assert.assertFalse("previous file in directory "+ newFileName +" present after move op", newPathExists.get());

        byte[] inDirContents =  Files.readAllBytes(inDir);

        Assert.assertTrue("Correct file contents after move to another directory", Arrays.equals(inDirContents, msg.getBytes()));
    }

    private void fileTest(int length, Random random)  throws IOException {
        byte[] data = new byte[length];
        random.nextBytes(data);

        String filename = randomUUID().toString();
        Path path = home.resolve(filename);

        Files.write(path, data);

        byte[] contents = Files.readAllBytes(path);

        Assert.assertTrue("Correct file contents for length "+ length, Arrays.equals(data, contents));
    }

    @Test
    public void readWriteTest() throws IOException {
        Random  random =  new Random(666); // repeatable with same seed
        for (int power = 5; power < 20; power++) {
            int length =  (int) Math.pow(2, power);
            length +=  random.nextInt(length);
            fileTest(length, random);
        }
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
