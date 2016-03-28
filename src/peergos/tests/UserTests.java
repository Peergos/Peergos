package peergos.tests;

import org.junit.*;
import static org.junit.Assert.*;

import peergos.corenode.*;
import peergos.crypto.*;
import peergos.user.*;
import peergos.user.fs.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

public class UserTests {

    private static int N_CHUNKS = 10;
    public static int RANDOM_SEED = 666;

    private static final Logger LOG = Logger.getGlobal();

    private static Random random = new Random(RANDOM_SEED);

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

    public UserContext ensureSignedUp(String username, String password) throws IOException {
        DHTClient.HTTP dht = new DHTClient.HTTP(new URL("http://localhost:8000/"));
        Btree.HTTP btree = new Btree.HTTP(new URL("http://localhost:8000/"));
        HTTPCoreNode coreNode = new HTTPCoreNode(new URL("http://localhost:8000/"));
        UserContext userContext = UserContext.ensureSignedUp(username, password, dht, btree, coreNode);
        return userContext;
    }

    public void add(String path) {

    }

    public  byte[] read(String path) {
        return null;
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
        byte[] data = randomData(0x1000);
        Files.write(tmpPath, data);

        boolean b = userRoot.uploadFile(name, tmpPath.toFile(), context, (l) -> {});

        assertTrue("file upload", b);

        Optional<FileTreeNode> opt = userRoot.getChildren(context)
                .stream()
                .filter(e -> {
                    try {
                        return e.getFileProperties().name.equals(name);
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                        return false;
                    }
                }).findFirst();

        assertTrue("found uploaded file", opt.isPresent());

        FileTreeNode fileTreeNode = opt.get();
        long size = fileTreeNode.getFileProperties().size;
        InputStream in = fileTreeNode.getInputStream(context, size, (l) -> {});
        byte[] retrievedData = readFully(in);

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


    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bout =  new ByteArrayOutputStream();
        byte[] b =  new  byte[0x1000];
        int nRead = -1;
        while ((nRead = in.read(b, 0, b.length)) != -1 )
            bout.write(b, 0, nRead);
        return bout.toByteArray();
    }
}
