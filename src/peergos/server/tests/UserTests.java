package peergos.server.tests;

import org.junit.*;
import static org.junit.Assert.*;

import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.storage.ResetableFileInputStream;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.server.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class UserTests {

    public static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public UserTests(String useIPFS, Random r) throws Exception {
        int webPort = 9000 + r.nextInt(1000);
        int corePort = 10000 + r.nextInt(1000);
        Args args = Args.parse(new String[]{"useIPFS", ""+useIPFS.equals("IPFS"), "-port", Integer.toString(webPort), "-corenodePort", Integer.toString(corePort)});
        Start.local(args);
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();
        // use insecure random otherwise tests take ages
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));
    }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> parameters() {
        Random r = new Random(0);
        return Arrays.asList(new Object[][] {
//                {"IPFS", r},
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
    public void differentLoginTypes() throws Exception {
        String username = "name";
        String password = "letmein";
        Crypto crypto = Crypto.initJava();
        List<ScryptEd25519Curve25519> params = Arrays.asList(
                new ScryptEd25519Curve25519(17, 8, 1, 96),
                new ScryptEd25519Curve25519(18, 8, 1, 96),
                new ScryptEd25519Curve25519(19, 8, 1, 96),
                new ScryptEd25519Curve25519(17, 9, 1, 96)
        );
        for (ScryptEd25519Curve25519 p: params) {
            long t1 = System.currentTimeMillis();
            UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer, p).get();
            long t2 = System.currentTimeMillis();
            System.out.println("User gen took " + (t2 - t1) + " mS");
            System.gc();
        }
    }

    @Test
    public void javascriptCompatible() throws IOException {
        String username = "test01";
        String password = "test01";

        UserUtil.generateUser(username, password, new ScryptJava(), new Salsa20Poly1305.Java(),
                new SafeRandom.Java(), new Ed25519.Java(), new Curve25519.Java(), UserGenerationAlgorithm.getDefault()).thenAccept(userWithRoot -> {
		    PublicSigningKey expected = PublicSigningKey.fromString("7HvEWP6yd1UD8rOorfFrieJ8S7yC8+l3VisV9kXNiHmI7Eav7+3GTRSVBRCymItrzebUUoCi39M6rdgeOU9sXXFD");
		    if (! expected.equals(userWithRoot.getUser().publicSigningKey))
		        throw new IllegalStateException("Generated user diferent from the Javascript! \n"+userWithRoot.getUser().publicSigningKey + " != \n"+expected);
        });
    }

    @Test
    public void randomSignup() throws Exception {
        String username = "test" + (System.currentTimeMillis() % 10000);
        String password = "password";
        ensureSignedUp(username, password, network, crypto);
    }

    @Test
    public void singleSignUp() throws Exception {
        // This is to ensure a user can't accidentally sign in rather than login and overwrite all their data
        String username = "test" + (System.currentTimeMillis() % 10000);
        String password = "password";
        ensureSignedUp(username, password, network, crypto);
        CompletableFuture<UserContext> secondSignup = UserContext.signUp(username, password, network, crypto);

        Assert.assertTrue("Second sign up fails", secondSignup.isCompletedExceptionally());
    }

    @Test
    public void changePassword() throws Exception {
        String username = "test" + (System.currentTimeMillis() % 10000);
        String password = "password";
        UserContext userContext = ensureSignedUp(username, password, network, crypto);
        String newPassword = "newPassword";
        userContext.changePassword(password, newPassword, UserGenerationAlgorithm.getDefault(), UserGenerationAlgorithm.getDefault());
        ensureSignedUp(username, newPassword, network, crypto);

    }
    @Test
    public void changePasswordFAIL() throws Exception {
        String username = "test" + (System.currentTimeMillis() % 10000);
        String password = "password";
        UserContext userContext = ensureSignedUp(username, password, network, crypto);
        String newPassword = "passwordtest";
        UserContext newContext = userContext.changePassword(password, newPassword, UserGenerationAlgorithm.getDefault(), UserGenerationAlgorithm.getDefault()).get();

        try {
            UserContext oldContext = ensureSignedUp(username, password, network, crypto);
        } catch (Exception e) {
            if (! e.getMessage().contains("Invalid encryption!"))
                throw e;
        }
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return UserContext.ensureSignedUp(username, password, network, crypto).get();
    }

    @Test
    public void writeReadVariations() throws Exception {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "somedata.txt";
        // write empty file
        byte[] data = new byte[0];
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context, l -> {}, context.fragmenter()).get();
        checkFileContents(data, userRoot.getDescendentByPath(filename, context).get().get(), context);

        // write small 1 chunk file
        byte[] data2 = "This is a small amount of data".getBytes();
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data2), 0, data2.length, context, l -> {}, context.fragmenter()).get();
        checkFileContents(data2, userRoot.getDescendentByPath(filename, context).get().get(), context);

        // check file size
        assertTrue("File size", data2.length == userRoot.getDescendentByPath(filename, context).get().get().getFileProperties().size);
        assertTrue("File size", data2.length == context.getByPath(username + "/" + filename).get().get().getFileProperties().size);

        // extend file within existing chunk
        byte[] data3 = new byte[128 * 1024];
        new Random().nextBytes(data3);
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data3), 0, data3.length, context, l -> {}, context.fragmenter()).get();
        checkFileContents(data3, userRoot.getDescendentByPath(filename, context).get().get(), context);

        // insert data in the middle
        byte[] data4 = "some data to insert somewhere".getBytes();
        int startIndex = 100 * 1024;
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data4), startIndex, startIndex + data4.length, context, l -> {}, context.fragmenter()).get();
        System.arraycopy(data4, 0, data3, startIndex, data4.length);
        checkFileContents(data3, userRoot.getDescendentByPath(filename, context).get().get(), context);

        //rename
        String newname = "newname.txt";
        userRoot.getDescendentByPath(filename, context).get().get().rename(newname, context, userRoot).get();
        checkFileContents(data3, userRoot.getDescendentByPath(newname, context).get().get(), context);
        // check from the root as well
        checkFileContents(data3, context.getByPath(username + "/" + newname).get().get(), context);
        // check from a fresh log in too
        UserContext context2 = ensureSignedUp(username, password, network, crypto);
        Optional<FileTreeNode> renamed = context2.getByPath(username + "/" + newname).get();
        checkFileContents(data3, renamed.get(), context);
    }

    @Test
    public void mediumFileWrite() throws Exception {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[0];
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context, l -> {}, context.fragmenter());

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        new Random().nextBytes(data5);
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data5), 0, data5.length, context, l -> {} , context.fragmenter());
        checkFileContents(data5, userRoot.getDescendentByPath(filename, context).get().get(), context);
        assertTrue("10MiB file size", data5.length == userRoot.getDescendentByPath(filename, context).get().get().getFileProperties().size);

        // insert data in the middle of second chunk
        System.out.println("\n***** Mid 2nd chunk write test");
        byte[] dataInsert = "some data to insert somewhere else".getBytes();
        int start = 5*1024*1024 + 4*1024;
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(dataInsert), start, start + dataInsert.length, context, l -> {}, context.fragmenter());
        System.arraycopy(dataInsert, 0, data5, start, dataInsert.length);
        checkFileContents(data5, userRoot.getDescendentByPath(filename, context).get().get(), context);
    }

    @Test
    public void writeTiming() throws Exception {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[0];
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context, l -> {}, context.fragmenter());

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        new Random().nextBytes(data5);
        long t1 = System.currentTimeMillis();
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data5), 0, data5.length, context, l -> {}, context.fragmenter());
        long t2 = System.currentTimeMillis();
        System.out.println("Write time per chunk " + (t2-t1)/2 + "mS");
        Assert.assertTrue("Timely write", (t2-t1)/2 < 20000);
    }

    @Test
    public void publicLinkToFile() throws Exception {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[128*1024];
        new Random().nextBytes(data);
        long t1 = System.currentTimeMillis();
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), 0, data.length, context, l -> {}, context.fragmenter());
        long t2 = System.currentTimeMillis();
        String path = "/" + username + "/" + filename;
        FileTreeNode file = context.getByPath(path).get().get();
        String link = file.toLink();
        UserContext linkContext = UserContext.fromPublicLink(link, network, crypto).get();
        Optional<FileTreeNode> fileThroughLink = linkContext.getByPath(path).get();
        Assert.assertTrue("File present through link", fileThroughLink.isPresent());
    }

    @Test
    public void publicLinkToDir() throws Exception {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[128*1024];
        new Random().nextBytes(data);
        long t1 = System.currentTimeMillis();
        String dirName = "subdir";
        userRoot.mkdir(dirName, context, false).get();
        FileTreeNode subdir = context.getByPath("/" + username + "/" + dirName).get().get();
        String anotherDirName = "anotherDir";
        subdir.mkdir(anotherDirName, context, false).get();
        FileTreeNode anotherDir = context.getByPath("/" + username + "/" + dirName + "/" + anotherDirName).get().get();
        anotherDir.uploadFile(filename, new AsyncReader.ArrayBacked(data), 0, data.length, context, l -> {}, context.fragmenter());
        long t2 = System.currentTimeMillis();
        String path = "/" + username + "/" + dirName + "/" + anotherDirName;
        FileTreeNode theDir = context.getByPath(path).get().get();
        String link = theDir.toLink();
        UserContext linkContext = UserContext.fromPublicLink(link, network, crypto).get();
        String entryPath = linkContext.getEntryPath().get();
        Assert.assertTrue("public link to folder has correct entry path", entryPath.equals(path));

        Optional<FileTreeNode> fileThroughLink = linkContext.getByPath(path + "/" + filename).get();
        Assert.assertTrue("File present through link", fileThroughLink.isPresent());
    }

    // This one takes a while, so disable most of the time
//    @Test
    public void hugeFolder() throws Exception {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();
        List<String> names = new ArrayList<>();
        IntStream.range(0, 2000).forEach(i -> names.add(randomString()));

        for (String filename: names) {
            userRoot.mkdir(filename, context, false, context.crypto.random);
        }
    }

    private static void checkFileContents(byte[] expected, FileTreeNode f, UserContext context) throws Exception {
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context, f.getFileProperties().size, l-> {}).get(), f.getSize()).get();
        assertTrue("Correct contents", Arrays.equals(retrievedData, expected));
    }


    @Test
    public void readWriteTest() throws Exception {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        Set<FileTreeNode> children = userRoot.getChildren(context).get();

        children.stream()
                .map(FileTreeNode::toString)
                .forEach(System.out::println);

        String name = randomString();
        Path tmpPath = createTmpFile(name);
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining
        Files.write(tmpPath, data);

        File tmpFile = tmpPath.toFile();
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(tmpFile);
        boolean b = userRoot.uploadFile(name, resetableFileInputStream, tmpFile.length(), context, (l) -> {}, context.fragmenter()).get();

        assertTrue("file upload", b);

        Optional<FileTreeNode> opt = userRoot.getChildren(context).get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(name))
                .findFirst();

        assertTrue("found uploaded file", opt.isPresent());

        FileTreeNode fileTreeNode = opt.get();
        long size = fileTreeNode.getFileProperties().size;
        AsyncReader in = fileTreeNode.getInputStream(context, size, (l) -> {}).get();
        byte[] retrievedData = Serialize.readFully(in, fileTreeNode.getSize()).get();

        boolean  dataEquals = Arrays.equals(data, retrievedData);

        assertTrue("retrieved same data", dataEquals);
    }


    @Test
    public void deleteTest() throws Exception {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        Set<FileTreeNode> children = userRoot.getChildren(context).get();

        children.stream()
                .map(FileTreeNode::toString)
                .forEach(System.out::println);

        String name = randomString();
        Path tmpPath = createTmpFile(name);
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining
        Files.write(tmpPath, data);

        File tmpFile = tmpPath.toFile();
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(tmpFile);

        boolean b = userRoot.uploadFile(name, resetableFileInputStream, tmpFile.length(), context, (l) -> {}, context.fragmenter()).get();
        String otherName = name + ".other";
        boolean b2 = userRoot.uploadFile(otherName, resetableFileInputStream, tmpFile.length(), context, (l) -> {}, context.fragmenter()).get();

        assertTrue("file upload", b);

        Optional<FileTreeNode> opt = userRoot.getChildren(context).get()
                        .stream()
                        .filter(e -> e.getFileProperties().name.equals(name))
                        .findFirst();

        assertTrue("found uploaded file", opt.isPresent());

        FileTreeNode fileTreeNode = opt.get();
        long size = fileTreeNode.getFileProperties().size;
        AsyncReader in = fileTreeNode.getInputStream(context, size, (l) -> {}).get();
        byte[] retrievedData = Serialize.readFully(in, fileTreeNode.getSize()).get();

        boolean  dataEquals = Arrays.equals(data, retrievedData);

        assertTrue("retrieved same data", dataEquals);

        //delete the file
        fileTreeNode.remove(context, userRoot).get();

        //re-create user-context
        UserContext context2 = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot2 = context2.getUserRoot().get();


        //check the file is no longer present
        boolean isPresent = userRoot2.getChildren(context2).get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(name))
                .findFirst()
                .isPresent();

        Assert.assertFalse("uploaded file is deleted", isPresent);


        //check content of other file in same directory that was not removed
        FileTreeNode otherFileTreeNode = userRoot2.getChildren(context2).get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(otherName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing other file"));

        AsyncReader asyncReader = otherFileTreeNode.getInputStream(context2, l -> {}).get();

        byte[] otherRetrievedData = Serialize.readFully(asyncReader, otherFileTreeNode.getSize()).get();
        boolean  otherDataEquals = Arrays.equals(data, otherRetrievedData);
        Assert.assertTrue("other file data is  intact", otherDataEquals);
    }


    @Test
    public void deleteDirectoryTest() throws Exception {
        String username = "test01";
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        Set<FileTreeNode> children = userRoot.getChildren(context).get();

        children.stream()
                .map(FileTreeNode::toString)
                .forEach(System.out::println);

        String folderName = "a_folder";
        boolean isSystemFolder = false;

        //create the directory
        userRoot.mkdir(folderName, context, isSystemFolder).get();

        FileTreeNode folderTreeNode = userRoot.getChildren(context)
                .get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(folderName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing created folder " + folderName));

        //remove the directory
        folderTreeNode.remove(context, userRoot).get();

        //ensure folder directory not  present
        boolean isPresent = userRoot.getChildren(context)
                .get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(folderName))
                .findFirst()
                .isPresent();

        Assert.assertFalse("folder not present after remove", isPresent);

        //can sign-in again
        try {
            UserContext context2 = ensureSignedUp(username, password, network, crypto);
            FileTreeNode userRoot2 = context2.getUserRoot().get();
        } catch (Exception ex) {
            fail("Failed to log-in and see user-root " + ex.getMessage());
        }

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
