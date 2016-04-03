package peergos.tests;

import org.junit.*;
import static org.junit.Assert.*;

import peergos.corenode.*;
import peergos.crypto.*;
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

public class UserTests {

    private static int N_CHUNKS = 10;
    public static int RANDOM_SEED = 666;
    public static int WEB_PORT = 9876;
    public static int CORE_PORT = 9753;

    private static final Logger LOG = Logger.getGlobal();

    private static Random random = new Random(RANDOM_SEED);

    @BeforeClass
    public static void startPeergos() throws Exception {
        Args.parse(new String[]{"useIPFS", "false", "-port", Integer.toString(WEB_PORT), "-corenodePort", Integer.toString(CORE_PORT)});
        Start.local();
        // use insecure random otherwise tests take ages
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random());
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

        UserWithRoot userWithRoot = UserUtil.generateUser(username, password);
        UserPublicKey expected = UserPublicKey.fromString("7HvEWP6yd1UD8rOorfFrieJ8S7yC8+l3VisV9kXNiHmI7Eav7+3GTRSVBRCymItrzebUUoCi39M6rdgeOU9sXXFD");
        if (! expected.equals(userWithRoot.getUser().toUserPublicKey()))
            throw new IllegalStateException("Generated user diferent from the Javascript! \n"+userWithRoot.getUser().toUserPublicKey() + " != \n"+expected);
    }

    @Test
    public void randomSignup() throws IOException {
        String username = "test" + (System.currentTimeMillis() % 10000);
        String password = "password";
        ensureSignedUp(username, password);
    }

    public static UserContext ensureSignedUp(String username, String password) throws IOException {
        DHTClient.HTTP dht = new DHTClient.HTTP(new URL("http://localhost:"+ WEB_PORT +"/"));
        Btree.HTTP btree = new Btree.HTTP(new URL("http://localhost:"+ WEB_PORT +"/"));
        HTTPCoreNode coreNode = new HTTPCoreNode(new URL("http://localhost:"+ WEB_PORT +"/"));
        UserContext userContext = UserContext.ensureSignedUp(username, password, dht, btree, coreNode);
        return userContext;
    }

    @Test
    public void social() throws IOException {
        UserContext u1 = ensureSignedUp("q", "q");
        UserContext u2 = ensureSignedUp("w", "w");
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests();
        Optional<FileTreeNode> friendRoot = u2.getTreeRoot().getDescendentByPath("/" + u1.username, u2);
        assertTrue("Friend root present after accepted follow request", friendRoot.isPresent());
        System.out.println();
    }

    public void add(String path) {

    }

    public  byte[] read(String path) {
        return null;
    }

    @Test
    public void writeReadVariations() throws IOException {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password);
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

        // extend file within existing chunk
        byte[] data3 = new byte[128*1024];
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
        checkFileContents(data3, context.getTreeRoot().getDescendentByPath(username + "/"+newname, context).get(), context);
        // check from a fresh log in too
        UserContext context2 = ensureSignedUp(username, password);
        checkFileContents(data3, context2.getTreeRoot().getDescendentByPath(username + "/"+newname, context2).get(), context);

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        new Random().nextBytes(data5);
        userRoot.uploadFile(newname, new ByteArrayInputStream(data5), 0, data5.length, context, l -> {});
        checkFileContents(data5, userRoot.getDescendentByPath(newname, context).get(), context);


        // insert data in the middle of second chunk
        System.out.println("\n***** Mid 2nd chunk write test");
        byte[] dataInsert = "some data to insert somewhere else".getBytes();
        int start = 5*1024*1024 + 4*1024;
        userRoot.uploadFile(newname, new ByteArrayInputStream(dataInsert), start, start + dataInsert.length, context, l -> {});
        System.arraycopy(dataInsert, 0, data5, start, dataInsert.length);
        checkFileContents(data5, userRoot.getDescendentByPath(newname, context).get(), context);
    }

    private static void runForAWhile() {
        for (int i=0; i < 600; i++)
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {}
    }

    private static void checkFileContents(byte[] expected, FileTreeNode f, UserContext context) throws IOException {
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context, f.getFileProperties().size, l-> {}));
        assertTrue("Correct contents", Arrays.equals(retrievedData, expected));
    }

    @Test
    public void readWriteTest() throws IOException {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password);
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

    private static String randomString() {
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
