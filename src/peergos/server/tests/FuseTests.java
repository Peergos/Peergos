package peergos.server.tests;
import java.util.logging.*;

import peergos.server.Main;
import peergos.server.util.Args;
import peergos.server.util.Logging;

import org.junit.*;
import static org.junit.Assert.*;
import static java.util.UUID.*;

import peergos.shared.*;
import peergos.server.fuse.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.lang.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.*;

public class FuseTests {
	private static final Logger LOG = Logging.LOG();
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

    @BeforeClass
    public static void init() throws Exception {
        Random  random  = new Random();
        int offset = random.nextInt(100);
//        int offset = 0;
        setWebPort(8888 + offset);
        setCorePort(7777 + offset);

        LOG.info("Using web-port "+ WEB_PORT);
        System.out.flush();

        Args args = Args.parse(new String[]{"useIPFS", "false",
                "-port", Integer.toString(WEB_PORT),
                "-corenodePort", Integer.toString(CORE_PORT)});

        Main.LOCAL.main(args);
        NetworkAccess network = NetworkAccess.buildJava(WEB_PORT).get();
        UserContext userContext = UserContext.ensureSignedUp(username, password, network, Crypto.initJava()).get();

        String mountPath = args.getArg("mountPoint", "/tmp/peergos/tmp");

        mountPoint = Paths.get(mountPath);
        mountPoint = mountPoint.resolve(UUID.randomUUID().toString());
        mountPoint.toFile().mkdirs();
        home = mountPoint.resolve(username);

        LOG.info("\n\nMountpoint "+ mountPoint +"\n\n");
//        PeergosFS peergosFS = new PeergosFS(userContext);
        PeergosFS peergosFS = new CachingPeergosFS(userContext);
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


        String[] stem = Stream.generate(() -> randomUUID().toString())
                .limit(2)
                .toArray(String[]::new);

        Path targetDir = Paths.get(initial.getParent().toString(), stem);
        targetDir.toFile().mkdirs();
        assertTrue("target dir exists", targetDir.toFile().isDirectory());

        Path target = targetDir.resolve(randomUUID().toString());
        assertFalse("target exists before move", target.toFile().exists());

        Files.move(initial, target);

        assertFalse("initial still exists", initial.toFile().exists());
        assertTrue("target exists after move", target.toFile().exists());
        byte[] targetData = Files.readAllBytes(target);

        assertTrue("target contents equal to initial contents", Arrays.equals(initialData, targetData));
    }

    @Test public void copyFileTest() throws IOException  {
        Path initial = createRandomFile(1024*1024*10);
        Path target = initial.getParent().resolve(randomUUID().toString());

        assertFalse("target exists", target.toFile().exists());
        Files.copy(initial, target);

        assertTrue("initial exists", initial.toFile().exists());
        assertTrue("target exists", target.toFile().exists());

        byte[] original = Files.readAllBytes(initial);
        byte[] copy = Files.readAllBytes(target);
        boolean contentEquals = Arrays.equals(original, copy);

        int firstDifferentIndex = firstDifferentindex(original, copy, 0);
        int lastDifferentIndex = lastDifferentindex(original, copy, original.length);

        byte[] diff = firstDifferentIndex > 0 ? Arrays.copyOfRange(copy, firstDifferentIndex, lastDifferentIndex) : new byte[0];

        assertTrue("initial and target contents equal", contentEquals);
    }

    @Test public void copyFileFromHostTest() throws IOException  {
        Path initial = Files.createTempFile(UUID.randomUUID().toString(), "rw");
        byte[] data = new byte[6*1024*1024];
        new Random(0).nextBytes(data);
        Files.write(initial, data);

        Path target = home.resolve(randomUUID().toString());

        assertFalse("target exists", target.toFile().exists());
        Files.copy(initial, target);

        assertTrue("initial exists", initial.toFile().exists());
        assertTrue("target exists", target.toFile().exists());

        boolean contentEquals = Arrays.equals(
                Files.readAllBytes(initial),
                Files.readAllBytes(target));

        assertTrue("initial and target contents equal", contentEquals);
    }

    @Test public void randomReadTest() throws IOException  {
        Path initial = Files.createTempFile(UUID.randomUUID().toString(), "rw");
        byte[] data = new byte[6*1024*1024];
        new Random(0).nextBytes(data);
        Files.write(initial, data);

        Path target = home.resolve(randomUUID().toString());

        assertFalse("target exists", target.toFile().exists());
        Files.copy(initial, target);

        Random r = new Random(0);
        for (int i=0; i < 20; i++) {
            int size = r.nextInt(100*1024);
            int offset = r.nextInt(data.length - size);

            byte[] original = readBytes(initial, offset, size);
            byte[] copy = readBytes(target, offset, size);

            boolean equal = Arrays.equals(original, copy);

            Assert.assertTrue("Same contents from " + offset, equal);
        }
    }

    private byte[] readBytes(Path file, long offset, int size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            byte[] res = new byte[size];
            int read = raf.read(res);
            if (read != size)
                throw new IllegalStateException("Only read " + read + " not " + size);
            return res;
        }
    }

    @Test public void removeTest() throws IOException {
        Path path = createRandomFile();
        assertTrue("path exists before delete", path.toFile().exists());
        Files.delete(path);
        assertFalse("path exists after delete", path.toFile().exists());
    }

    @Test public void writePastEnd() throws IOException {
        int length = 10 * 1024;
        Path path = createRandomFile(length);
        byte[] initial = Files.readAllBytes(path);
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
        raf.seek(2*length);
        byte[] tmp = new byte[length];
        Random rnd = new Random(666);
        rnd.nextBytes(tmp);
        raf.write(tmp);
        raf.close();
        byte[] expected = Arrays.copyOfRange(initial, 0, 3*length);
        System.arraycopy(tmp, 0, expected, 2*length, length);
        byte[] extendedContents = Files.readAllBytes(path);
        assertTrue("Correct contents", Arrays.equals(expected, extendedContents));
    }

    @Test public void truncateTest() throws IOException {
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

    @Test
    public  void lastModifiedTimeTest() throws IOException {
        Path path = createRandomFile();

        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime limit =  now.minusMonths(1);


        for (ZonedDateTime zdt = now; zdt.isAfter(limit); zdt =  zdt.minusDays(1)) {
            FileTime fileTime = FileTime.from(zdt.toInstant());
            Path path1 = Files.setLastModifiedTime(path, fileTime);
            FileTime found = Files.getLastModifiedTime(path);
            assertEquals("get(set(time)) = time for time = "+ zdt, fileTime.toMillis() / 1000 + zdt.getOffset().getTotalSeconds(), found.toMillis() / 1000);
        }
    }



    @Test public void mkdirsTest() throws IOException {

        String[] stem = Stream.generate(() -> randomUUID().toString())
                .limit(10)
                .toArray(String[]::new);

        Path path = Paths.get(home.toString(), stem);
        assertFalse("path exists initially", path.toFile().exists());

        path.toFile().mkdirs();

        assertTrue("path is directory", path.toFile().isDirectory());
    }

    @Test
    public  void rmdirTest() throws IOException {
        Path path = home
                .resolve(randomUUID().toString())
                .resolve(randomUUID().toString());


        assertFalse("dir exists initially",
                path.toFile().exists());

        path.toFile().mkdirs();

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

    private void fileTest(int length, Random random)  throws IOException {
        byte[] data = new byte[length];
        random.nextBytes(data);

        String filename = randomUUID().toString();
        Path path = home.resolve(filename);

        Files.write(path, data);

        byte[] contents = Files.readAllBytes(path);

        boolean equals = Arrays.equals(data, contents);
        String diff = equals ? "" : "Different at index " + firstDifferentindex(data, contents, 0);
        Assert.assertTrue("Correct file contents: length("+ contents.length +") expected("+length+") "+ diff, equals);
    }

    public static int lastDifferentindex(byte[] src, byte[] target, int start) {
        for (int i=start-1; i >= 0; i--) {
            if (i >= target.length)
                return i;
            if (src[i] != target[i])
                return i;
        }
        return -1;
    }

    public static int firstDifferentindex(byte[] src, byte[] target, int start) {
        for (int i=start; i < src.length; i++) {
            if (i >= target.length)
                return i;
            if (src[i] != target[i])
                return i;
        }
        return -1;
    }

    @Test
    public void readWriteTest() throws IOException {
        Random  random =  new Random(3); // repeatable with same seed 3 leads to failure with bulk upload at size of 137
        for (int power = 5; power < 20; power++) {
            int length =  (int) Math.pow(2, power);
            length +=  random.nextInt(length);
            fileTest(length, random);
        }
    }

    @AfterClass
    public static void shutdown() {
        if (fuseProcess != null)
            fuseProcess.close();
    }
}
