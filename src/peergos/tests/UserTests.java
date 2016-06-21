package peergos.tests;

import org.junit.*;
import static org.junit.Assert.*;

import org.junit.runner.*;
import org.junit.runners.*;
import peergos.corenode.*;
import peergos.crypto.*;
import peergos.crypto.asymmetric.curve25519.*;
import peergos.crypto.hash.*;
import peergos.crypto.random.*;
import peergos.crypto.symmetric.*;
import peergos.server.*;
import peergos.user.*;
import peergos.user.fs.*;
import peergos.util.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

@RunWith(Parameterized.class)
public class UserTests {

    public static int RANDOM_SEED = 666;
    private final int webPort;
    private final int corePort;

    private static Random random = new Random(RANDOM_SEED);

    public UserTests(String useIPFS, Random r) throws Exception {
        this.webPort = 9000 + r.nextInt(1000);
        this.corePort = 10000 + r.nextInt(1000);
        Args args = Args.parse(new String[]{"useIPFS", ""+useIPFS.equals("IPFS"), "-port", Integer.toString(webPort), "-corenodePort", Integer.toString(corePort)});
        Start.local(args);
        // use insecure random otherwise tests take ages
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));
    }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> parameters() {
        Random r = new Random(0);
        return Arrays.asList(new Object[][] {
                {"IPFS", r},
                {"RAM", r}
        });
    }

    static void setFinalStatic(Field field, Object newValue) throws Exception {
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.set(null, newValue);
    }

    @Test
    public void javascriptCompatible() throws IOException {
        String username = "test01";
        String password = "test01";

        UserWithRoot userWithRoot = UserUtil.generateUser(username, password, new ScryptJava(), new Salsa20Poly1305.Java(),
                new SafeRandom.Java(), new JavaEd25519(), new JavaCurve25519());
        UserPublicKey expected = UserPublicKey.fromString("7HvEWP6yd1UD8rOorfFrieJ8S7yC8+l3VisV9kXNiHmI7Eav7+3GTRSVBRCymItrzebUUoCi39M6rdgeOU9sXXFD");
        if (! expected.equals(userWithRoot.getUser().toUserPublicKey()))
            throw new IllegalStateException("Generated user diferent from the Javascript! \n"+userWithRoot.getUser().toUserPublicKey() + " != \n"+expected);
    }

    @Test
    public void randomSignup() throws IOException {
        String username = "test" + (System.currentTimeMillis() % 10000);
        String password = "password";
        ensureSignedUp(username, password, webPort);
    }

    @Test
    public void changePassword() throws IOException {
        String username = "test" + (System.currentTimeMillis() % 10000);
        String password = "password";
        UserContext userContext = ensureSignedUp(username, password, webPort);
        String newPassword = "newPassword";
        userContext.changePassword(newPassword);
        ensureSignedUp(username, newPassword, webPort);

    }
    @Test
    public void changePasswordFAIL() throws IOException {
        String username = "test" + (System.currentTimeMillis() % 10000);
        String password = "password";
        UserContext userContext = ensureSignedUp(username, password, webPort);
        String newPassword = "passwordtest";
        UserContext newContext = userContext.changePassword(newPassword);

        try {
            UserContext oldContext = ensureSignedUp(username, password, webPort);
        } catch (IllegalStateException e) {
            if (!e.getMessage().contains("username already registered"))
                throw e;
        }
    }

    public static UserContext ensureSignedUp(String username, String password, int webPort) throws IOException {
        return UserContext.ensureSignedUp(username, password, webPort, false);
    }

    @Test
    public void writeReadVariations() throws IOException {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, webPort);
        FileTreeNode userRoot = context.getUserRoot();

        String filename = "somedata.txt";
        // write empty file
        byte[] data = new byte[0];
        userRoot.uploadFile(filename, new ByteArrayInputStream(data), data.length, context, l -> {});
        checkFileContents(data, userRoot.getDescendentByPath(filename, context).get(), context);

        // write small 1 chunk file
        byte[] data2 = "This is a small amount of data".getBytes();
        userRoot.uploadFile(filename, new ByteArrayInputStream(data2), 0, data2.length, context, l -> {});
        checkFileContents(data2, userRoot.getDescendentByPath(filename, context).get(), context);

        // check file size
        assertTrue("File size", data2.length == userRoot.getDescendentByPath(filename, context).get().getFileProperties().size);
        assertTrue("File size", data2.length == context.getByPath(username + "/" + filename).get().getFileProperties().size);

        // extend file within existing chunk
        byte[] data3 = new byte[128 * 1024];
        new Random().nextBytes(data3);
        userRoot.uploadFile(filename, new ByteArrayInputStream(data3), 0, data3.length, context, l -> {});
        checkFileContents(data3, userRoot.getDescendentByPath(filename, context).get(), context);

        // insert data in the middle
        byte[] data4 = "some data to insert somewhere".getBytes();
        int startIndex = 100 * 1024;
        userRoot.uploadFile(filename, new ByteArrayInputStream(data4), startIndex, startIndex + data4.length, context, l -> {});
        System.arraycopy(data4, 0, data3, startIndex, data4.length);
        checkFileContents(data3, userRoot.getDescendentByPath(filename, context).get(), context);

        //rename
        String newname = "newname.txt";
        userRoot.getDescendentByPath(filename, context).get().rename(newname, context, userRoot);
        checkFileContents(data3, userRoot.getDescendentByPath(newname, context).get(), context);
        // check from the root as well
        checkFileContents(data3, context.getByPath(username + "/" + newname).get(), context);
        // check from a fresh log in too
        UserContext context2 = ensureSignedUp(username, password, webPort);
        checkFileContents(data3, context2.getByPath(username + "/" + newname).get(), context);
    }

    @Test
    public void mediumFileWrite() throws IOException {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, webPort);
        FileTreeNode userRoot = context.getUserRoot();

        String filename = "mediumfile.bin";
        byte[] data = new byte[0];
        userRoot.uploadFile(filename, new ByteArrayInputStream(data), data.length, context, l -> {});

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        new Random().nextBytes(data5);
        userRoot.uploadFile(filename, new ByteArrayInputStream(data5), 0, data5.length, context, l -> {});
        checkFileContents(data5, userRoot.getDescendentByPath(filename, context).get(), context);
        assertTrue("10MiB file size", data5.length == userRoot.getDescendentByPath(filename, context).get().getFileProperties().size);

        // insert data in the middle of second chunk
        System.out.println("\n***** Mid 2nd chunk write test");
        byte[] dataInsert = "some data to insert somewhere else".getBytes();
        int start = 5*1024*1024 + 4*1024;
        userRoot.uploadFile(filename, new ByteArrayInputStream(dataInsert), start, start + dataInsert.length, context, l -> {});
        System.arraycopy(dataInsert, 0, data5, start, dataInsert.length);
        checkFileContents(data5, userRoot.getDescendentByPath(filename, context).get(), context);
    }

    @Test
    public void writeTiming() throws IOException {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, webPort);
        FileTreeNode userRoot = context.getUserRoot();

        String filename = "mediumfile.bin";
        byte[] data = new byte[0];
        userRoot.uploadFile(filename, new ByteArrayInputStream(data), data.length, context, l -> {});

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        new Random().nextBytes(data5);
        long t1 = System.currentTimeMillis();
        userRoot.uploadFile(filename, new ByteArrayInputStream(data5), 0, data5.length, context, l -> {});
        long t2 = System.currentTimeMillis();
        System.out.println("Write time per chunk " + (t2-t1)/2 + "mS");
        Assert.assertTrue("Timely write", (t2-t1)/2 < 20000);
    }

    private static void checkFileContents(byte[] expected, FileTreeNode f, UserContext context) throws IOException {
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context, f.getFileProperties().size, l-> {}));
        assertTrue("Correct contents", Arrays.equals(retrievedData, expected));
    }

    @Test
    public void readWriteTest() throws IOException {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, webPort);
        FileTreeNode userRoot = context.getUserRoot();

        Set<FileTreeNode> children = userRoot.getChildren(context);

        children.stream()
                .map(FileTreeNode::toString)
                .forEach(System.out::println);

        String name = randomString();
        Path tmpPath = createTmpFile(name);
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining
        Files.write(tmpPath, data);

        boolean b = userRoot.uploadFile(name, tmpPath.toFile(), context, (l) -> {});

        assertTrue("file upload", b);

        Optional<FileTreeNode> opt = userRoot.getChildren(context)
                .stream()
                .filter(e -> e.getFileProperties().name.equals(name))
                .findFirst();

        assertTrue("found uploaded file", opt.isPresent());

        FileTreeNode fileTreeNode = opt.get();
        long size = fileTreeNode.getFileProperties().size;
        InputStream in = fileTreeNode.getInputStream(context, size, (l) -> {});
        byte[] retrievedData = Serialize.readFully(in);

        boolean  dataEquals = Arrays.equals(data, retrievedData);

        assertTrue("retrieved same data", dataEquals);
    }

    public static String randomString() {
        return UUID.randomUUID().toString();
    }

    private static byte[] randomData(int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }
    private static Path TMP_DIR = Paths.get("test","resources","tmp");

    private static void ensureTmpDir() {
        File dir = TMP_DIR.toFile();
        if (! dir.isDirectory() &&  ! dir.mkdirs())
            throw new IllegalStateException("Could not find or create specified tmp directory "+ TMP_DIR);
    }

    private static Path createTmpFile(String filename) throws IOException {
        ensureTmpDir();
        Path resolve = TMP_DIR.resolve(filename);
        File file = resolve.toFile();
        file.createNewFile();
        file.deleteOnExit();
        return resolve;
    }
}
