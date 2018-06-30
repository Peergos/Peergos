package peergos.server.tests;
import java.util.logging.*;

import org.junit.*;
import static org.junit.Assert.*;

import peergos.server.storage.*;
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
import peergos.shared.util.Exceptions;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public abstract class UserTests {
	private static final Logger LOG = Logger.getGlobal();

    public static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public UserTests(String useIPFS, Random r) throws Exception {
        int portMin = 9000;
        int portRange = 8000;
        int webPort = portMin + r.nextInt(portRange);
        int corePort = portMin + portRange + r.nextInt(portRange);
        int socialPort = portMin + portRange + r.nextInt(portRange);

        Args args = Args.parse(new String[]{
                "useIPFS", ""+useIPFS.equals("IPFS"),
                "-port", Integer.toString(webPort),
                "-corenodePort", Integer.toString(corePort),
                "-socialnodePort", Integer.toString(socialPort)
        });

        Start.LOCAL.main(args);
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();
    }

    private String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 10000);
    }

    @Test
    public void serializationSizesSmall() {
        SigningKeyPair signer = SigningKeyPair.random(crypto.random, crypto.signer);
        byte[] rawSignPub = signer.publicSigningKey.serialize(); // 36
        byte[] rawSignSecret = signer.secretSigningKey.serialize(); // 68
        byte[] rawSignBoth = signer.serialize(); // 105
        BoxingKeyPair boxer = BoxingKeyPair.random(crypto.random, crypto.boxer);
        byte[] rawBoxPub = boxer.publicBoxingKey.serialize(); // 36
        byte[] rawBoxSecret = boxer.secretBoxingKey.serialize(); // 36
        byte[] rawBoxBoth = boxer.serialize(); // 73
        SymmetricKey sym = SymmetricKey.random();
        byte[] rawSym = sym.serialize(); // 37
        Assert.assertTrue("Serialization overhead isn't too much", rawSignPub.length <= 32 + 4);
        Assert.assertTrue("Serialization overhead isn't too much", rawSignSecret.length <= 64 + 4);
        Assert.assertTrue("Serialization overhead isn't too much", rawSignBoth.length <= 96 + 9);
        Assert.assertTrue("Serialization overhead isn't too much", rawBoxPub.length <= 32 + 4);
        Assert.assertTrue("Serialization overhead isn't too much", rawBoxSecret.length <= 32 + 4);
        Assert.assertTrue("Serialization overhead isn't too much", rawBoxBoth.length <= 64 + 9);
        Assert.assertTrue("Serialization overhead isn't too much", rawSym.length <= 33 + 4);
    }

    @Test
    public void differentLoginTypes() throws Exception {
        String username = generateUsername();
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
            LOG.info("User gen took " + (t2 - t1) + " mS");
            System.gc();
        }
    }

    @Test
    public void javascriptCompatible() throws IOException {
        String username = generateUsername();
        String password = "test01";

        UserUtil.generateUser(username, password, new ScryptJava(), new Salsa20Poly1305.Java(),
                new SafeRandom.Java(), new Ed25519.Java(), new Curve25519.Java(), UserGenerationAlgorithm.getDefault()).thenAccept(userWithRoot -> {
		    PublicSigningKey expected = PublicSigningKey.fromString("7HvEWP6yd1UD8rOorfFrieJ8S7yC8+l3VisV9kXNiHmI7Eav7+3GTRSVBRCymItrzebUUoCi39M6rdgeOU9sXXFD");
		    if (! expected.equals(userWithRoot.getUser().publicSigningKey))
		        throw new IllegalStateException("Generated user different from the Javascript! \n"+userWithRoot.getUser().publicSigningKey + " != \n"+expected);
        });
    }

    @Test
    public void randomSignup() throws Exception {
        String username = generateUsername();
        String password = "password";
        ensureSignedUp(username, password, network, crypto);
    }

    @Test
    public void singleSignUp() throws Exception {
        // This is to ensure a user can't accidentally sign in rather than login and overwrite all their data
        String username = generateUsername();
        String password = "password";
        ensureSignedUp(username, password, network, crypto);
        CompletableFuture<UserContext> secondSignup = UserContext.signUp(username, password, network, crypto);

        Assert.assertTrue("Second sign up fails", secondSignup.isCompletedExceptionally());
    }

    @Test
    public void duplicateSignUp() throws Exception {
        UserContext.ensureSignedUp("q", "q", network, crypto).get();
        try {
            UserContext.signUp("q", "w", network, crypto).get();
        } catch (Exception e) {
            if (! e.getMessage().contains("User already exists"))
                Assert.fail("Incorrect error message");
        }
    }

    @Test
    public void repeatedSignUp() throws Exception {
        UserContext.ensureSignedUp("q", "q", network, crypto).get();
        try {
            UserContext.signUp("q", "q", network, crypto).get();
        } catch (Exception e) {
            if (!Exceptions.getRootCause(e).getMessage().contains("User already exists"))
                Assert.fail("Incorrect error message");
        }
    }

    @Test
    public void changePassword() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext userContext = ensureSignedUp(username, password, network, crypto);
        String newPassword = "newPassword";
        userContext.changePassword(password, newPassword).get();
        ensureSignedUp(username, newPassword, network, crypto);
    }

    @Test
    public void changePasswordFAIL() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext userContext = ensureSignedUp(username, password, network, crypto);
        String newPassword = "passwordtest";
        UserContext newContext = userContext.changePassword(password, newPassword).get();

        try {
            UserContext oldContext = ensureSignedUp(username, password, network, crypto);
        } catch (Exception e) {
            if (! e.getMessage().contains("Incorrect password"))
                throw e;
        }
    }

    @Test
    public void changeLoginAlgorithm() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext userContext = ensureSignedUp(username, password, network, crypto);
        UserGenerationAlgorithm algo = userContext.getKeyGenAlgorithm().get();
        ScryptEd25519Curve25519 newAlgo = new ScryptEd25519Curve25519(19, 8, 1, 96);
        userContext.changePassword(password, password, algo, newAlgo).get();
        ensureSignedUp(username, password, network, crypto);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return UserContext.ensureSignedUp(username, password, network, crypto).get();
    }

    @Test
    public void writeReadVariations() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "somedata.txt";
        // write empty file
        byte[] data = new byte[0];
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();
        checkFileContents(data, context.getUserRoot().get().getDescendentByPath(filename, context.network).get().get(), context);

        // write small 1 chunk file
        byte[] data2 = "This is a small amount of data".getBytes();
        FileTreeNode updatedRoot = context.getUserRoot().get().uploadFileSection(filename, new AsyncReader.ArrayBacked(data2), 0, data2.length, context.network,
                context.crypto.random, l -> {
                }, context.fragmenter()).get();
        checkFileContents(data2, updatedRoot.getDescendentByPath(filename, context.network).get().get(), context);

        // check multiple read calls  in one chunk
        checkFileContentsChunked(data2, updatedRoot.getDescendentByPath(filename, context.network).get().get(), context, 3);
        // check file size
        // assertTrue("File size", data2.length == userRoot.getDescendentByPath(filename,context.network).get().get().getFileProperties().size);


        // check multiple read calls in multiple chunks
        int bigLength = Chunk.MAX_SIZE * 3;
        byte[] bigData = new byte[bigLength];
        random.nextBytes(bigData);
        FileTreeNode updatedRoot2 = updatedRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(bigData), 0, bigData.length, context.network,
                context.crypto.random, l -> {
                }, context.fragmenter()).get();
        checkFileContentsChunked(bigData,
                updatedRoot2.getDescendentByPath(filename, context.network).get().get(),
                context,
                5);
        assertTrue("File size", bigData.length == context.getByPath(username + "/" + filename).get().get().getFileProperties().size);

        // extend file within existing chunk
        byte[] data3 = new byte[128 * 1024];
        new Random().nextBytes(data3);
        String otherName = "other"+filename;
        FileTreeNode updatedRoot3 = updatedRoot2.uploadFileSection(otherName, new AsyncReader.ArrayBacked(data3), 0, data3.length, context.network,
                context.crypto.random, l -> {
                }, context.fragmenter()).get();
        assertTrue("File size", data3.length == context.getByPath(username + "/" + otherName).get().get().getFileProperties().size);
        checkFileContents(data3, updatedRoot3.getDescendentByPath(otherName, context.network).get().get(), context);

        // insert data in the middle
        byte[] data4 = "some data to insert somewhere".getBytes();
        int startIndex = 100 * 1024;
        FileTreeNode updatedRoot4 = updatedRoot3.uploadFileSection(otherName, new AsyncReader.ArrayBacked(data4), startIndex, startIndex + data4.length,
                context.network, context.crypto.random, l -> {}, context.fragmenter()).get();
        System.arraycopy(data4, 0, data3, startIndex, data4.length);
        checkFileContents(data3, updatedRoot4.getDescendentByPath(otherName, context.network).get().get(), context);

        //rename
        String newname = "newname.txt";
        updatedRoot4.getDescendentByPath(otherName, context.network).get().get()
                .rename(newname, context.network, updatedRoot4).get();
        checkFileContents(data3, updatedRoot4.getDescendentByPath(newname, context.network).get().get(), context);
        // check from the root as well
        checkFileContents(data3, context.getByPath(username + "/" + newname).get().get(), context);
        // check from a fresh log in too
        UserContext context2 = ensureSignedUp(username, password, network.clear(), crypto);
        Optional<FileTreeNode> renamed = context2.getByPath(username + "/" + newname).get();
        checkFileContents(data3, renamed.get(), context);
    }

    @Test
    public void concurrentWritesToDir() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);

        // write empty file
        int concurrency = 8;
        int fileSize = 1024;
        ForkJoinPool pool = new ForkJoinPool(concurrency);
        Set<CompletableFuture<Boolean>> futs = IntStream.range(0, concurrency)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    byte[] data = randomData(fileSize);
                    String filename = i + ".bin";
                    try {
                        FileTreeNode userRoot = context.getUserRoot().get();
                        FileTreeNode result = userRoot.uploadFile(filename,
                                new AsyncReader.ArrayBacked(data),
                                data.length, context.network, context.crypto.random, l -> {}, context.fragmenter()).get();
                        Optional<FileTreeNode> childOpt = result.getChild(filename, network).get();
                        checkFileContents(data, childOpt.get(), context);
                        LOG.info("Finished a file");
                        return true;
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }, pool)).collect(Collectors.toSet());

        boolean success = Futures.combineAll(futs).get().stream().reduce(true, (a, b) -> a && b);

        Set<FileTreeNode> files = context.getUserRoot().get().getChildren(context.network).get();
        Set<String> names = files.stream().filter(f -> ! f.getFileProperties().isHidden).map(f -> f.getName()).collect(Collectors.toSet());
        Set<String> expectedNames = IntStream.range(0, concurrency).mapToObj(i -> i + ".bin").collect(Collectors.toSet());
        Assert.assertTrue("All children present and accounted for: " + names, names.equals(expectedNames));
    }

    @Test
    public void concurrentMkdirs() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);

        // write empty file
        int concurrency = 8;
        int fileSize = 1024;
        ForkJoinPool pool = new ForkJoinPool(concurrency);
        Set<CompletableFuture<Boolean>> futs = IntStream.range(0, concurrency)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    byte[] data = randomData(fileSize);
                    String filename = "folder" + i;
                    try {
                        FileTreeNode userRoot = context.getUserRoot().get();
                        FileTreeNode result = userRoot.uploadFile(filename,
                                new AsyncReader.ArrayBacked(data),
                                data.length, context.network, context.crypto.random, l -> {}, context.fragmenter()).get();
                        return true;
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }, pool)).collect(Collectors.toSet());

        boolean success = Futures.combineAll(futs).get().stream().reduce(true, (a, b) -> a && b);

        Set<FileTreeNode> files = context.getUserRoot().get().getChildren(context.network).get();
        Set<String> names = files.stream().filter(f -> ! f.getFileProperties().isHidden).map(f -> f.getName()).collect(Collectors.toSet());
        Set<String> expectedNames = IntStream.range(0, concurrency).mapToObj(i -> "folder" + i).collect(Collectors.toSet());
        Assert.assertTrue("All children present and accounted for: " + names, names.equals(expectedNames));
    }

    @Test
    public void concurrentWritesToFile() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);

        // write a n chunk file, then concurrently modify each of the chunks
        int concurrency = 2;
        int CHUNK_SIZE = 5 * 1024 * 1024;
        int fileSize = concurrency * CHUNK_SIZE;
        String filename = "afile.bin";
        FileTreeNode newRoot = context.getUserRoot().get().uploadFile(filename,
                                new AsyncReader.ArrayBacked(randomData(fileSize)),
                                fileSize, context.network, context.crypto.random, l -> {}, context.fragmenter()).get();

        List<byte[]> sections = Collections.synchronizedList(new ArrayList<>(concurrency));
        for (int i=0; i < concurrency; i++)
            sections.add(null);

        ForkJoinPool pool = new ForkJoinPool(concurrency);
        Set<CompletableFuture<Boolean>> futs = IntStream.range(0, concurrency)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        FileTreeNode userRoot = context.getUserRoot().get();

                        byte[] data = randomData(CHUNK_SIZE);
                        FileTreeNode result = userRoot.uploadFileSection(filename,
                                new AsyncReader.ArrayBacked(data),
                                i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE,
                                context.network, context.crypto.random, l -> {}, context.fragmenter()).get();
                        Optional<FileTreeNode> childOpt = result.getChild(filename, network).get();
                        sections.set(i, data);
                        return true;
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }, pool)).collect(Collectors.toSet());

        boolean success = Futures.combineAll(futs).get().stream().reduce(true, (a, b) -> a && b);

        FileTreeNode file = context.getByPath("/" + username + "/" + filename).get().get();
        byte[] all = new byte[concurrency * CHUNK_SIZE];
        for (int i=0; i < concurrency; i++)
            System.arraycopy(sections.get(i), 0, all, i * CHUNK_SIZE, CHUNK_SIZE);
        checkFileContents(all, file, context);
    }

    @Test
    public void duplicateConcurrentWritesToDir() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);

        // write empty file
        int concurrency = 8;
        int fileSize = 1024;
        String prefix = "afile";
        String suffix = "bin";
        String filename = prefix + "." + suffix;

        ForkJoinPool pool = new ForkJoinPool(concurrency);
        Set<CompletableFuture<Boolean>> futs = IntStream.range(0, concurrency)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    byte[] data = randomData(fileSize);
                    try {
                        FileTreeNode userRoot = context.getUserRoot().get();
                        FileTreeNode result = userRoot.uploadFile(filename,
                                new AsyncReader.ArrayBacked(data),
                                data.length, context.network, context.crypto.random, l -> {}, context.fragmenter()).get();
                        return true;
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }, pool)).collect(Collectors.toSet());

        boolean success = Futures.combineAll(futs).get().stream().reduce(true, (a, b) -> a && b);

        Set<FileTreeNode> files = context.getUserRoot().get().getChildren(context.network).get();
        Set<String> names = files.stream().filter(f -> ! f.getFileProperties().isHidden).map(f -> f.getName()).collect(Collectors.toSet());
        Set<String> expectedNames = Stream.concat(IntStream.range(1, concurrency)
                .mapToObj(i -> prefix + "[" + i + "]." + suffix), Stream.of(filename))
                .collect(Collectors.toSet());
        Assert.assertTrue("All children present and accounted for: " + names, names.equals(expectedNames));
    }

    @Test
    public void smallFileWrite() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "small.txt";
        byte[] data = "G'day mate".getBytes();
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();
        String mimeType = context.getByPath(Paths.get(username, filename).toString()).get().get().getFileProperties().mimeType;
        Assert.assertTrue("Incorrect mimetype: " + mimeType, mimeType.equals("text/plain"));
    }

    @Test
    public void javaThumbnail() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "small.png";
        byte[] data = Files.readAllBytes(Paths.get("assets", "logo.png"));
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();
        FileTreeNode file = context.getByPath(Paths.get(username, filename).toString()).get().get();
        String thumbnail = file.getBase64Thumbnail();
        Assert.assertTrue("Has thumbnail", thumbnail.length() > 0);
    }

    @Ignore // until we figure out how to manage javafx in tests
    @Test
    public void javaVideoThumbnail() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "trailer.mp4";
        byte[] data = Files.readAllBytes(Paths.get("assets", filename));
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();
        FileTreeNode file = context.getByPath(Paths.get(username, filename).toString()).get().get();
        String thumbnail = file.getBase64Thumbnail();
        Assert.assertTrue("Has thumbnail", thumbnail.length() > 0);
    }

    @Test
    public void mediumFileWrite() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[0];
        FileTreeNode userRoot2 = userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto.random, l -> {
                }, context.fragmenter()).get();

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        random.nextBytes(data5);
        FileTreeNode userRoot3 = userRoot2.uploadFileSection(filename, new AsyncReader.ArrayBacked(data5), 0, data5.length, context.network,
                context.crypto.random, l -> {
                }, context.fragmenter()).get();
        checkFileContents(data5, userRoot3.getDescendentByPath(filename, context.network).get().get(), context);
        assertTrue("10MiB file size", data5.length == userRoot3.getDescendentByPath(filename,
                context.network).get().get().getFileProperties().size);

        // insert data in the middle of second chunk
        LOG.info("\n***** Mid 2nd chunk write test");
        byte[] dataInsert = "some data to insert somewhere else".getBytes();
        int start = 5*1024*1024 + 4*1024;
        FileTreeNode userRoot4 = userRoot3.uploadFileSection(filename, new AsyncReader.ArrayBacked(dataInsert), start, start + dataInsert.length,
                context.network, context.crypto.random, l -> {
                }, context.fragmenter()).get();
        System.arraycopy(dataInsert, 0, data5, start, dataInsert.length);
        checkFileContents(data5, userRoot4.getDescendentByPath(filename, context.network).get().get(), context);

        // check used space
        PublicKeyHash signer = context.signer.publicKeyHash;
        long totalSpaceUsed = context.getTotalSpaceUsed(signer).get();
        Assert.assertTrue("Correct used space", totalSpaceUsed > 10*1024*1024);
    }

    @Test
    public void writeTiming() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[0];
        FileTreeNode updatedRoot = userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                context.network, context.crypto.random, l -> { }, context.fragmenter()).get();

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        random.nextBytes(data5);
        long t1 = System.currentTimeMillis();
        updatedRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(data5), 0, data5.length,
                context.network, context.crypto.random, l -> {}, context.fragmenter()).get();
        long t2 = System.currentTimeMillis();
        LOG.info("Write time per chunk " + (t2-t1)/2 + "mS");
        Assert.assertTrue("Timely write", (t2-t1)/2 < 20000);
    }

    @Test
    public void publicLinkToFile() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[128*1024];
        random.nextBytes(data);
        long t1 = System.currentTimeMillis();
        userRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(data), 0, data.length, context.network, context.crypto.random, l -> {}, context.fragmenter()).get();
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
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[128*1024];
        random.nextBytes(data);
        String dirName = "subdir";
        userRoot.mkdir(dirName, context.network, false, context.crypto.random).get();
        FileTreeNode subdir = context.getByPath("/" + username + "/" + dirName).get().get();
        String anotherDirName = "anotherDir";
        subdir.mkdir(anotherDirName, context.network, false, context.crypto.random).get();
        FileTreeNode anotherDir = context.getByPath("/" + username + "/" + dirName + "/" + anotherDirName).get().get();
        anotherDir.uploadFileSection(filename, new AsyncReader.ArrayBacked(data), 0, data.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();

        String path = "/" + username + "/" + dirName + "/" + anotherDirName;
        FileTreeNode theDir = context.getByPath(path).get().get();
        String link = theDir.toLink();
        UserContext linkContext = UserContext.fromPublicLink(link, network, crypto).get();
        String entryPath = linkContext.getEntryPath().get();
        Assert.assertTrue("public link to folder has correct entry path", entryPath.equals(path));

        Optional<FileTreeNode> fileThroughLink = linkContext.getByPath(path + "/" + filename).get();
        Assert.assertTrue("File present through link", fileThroughLink.isPresent());
    }

    @Test
    public void rename() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String dirName = "subdir";
        userRoot.mkdir(dirName, context.network, false, context.crypto.random).get();
        FileTreeNode subdir = context.getByPath("/" + username + "/" + dirName).get().get();
        String anotherDirName = "anotherDir";
        subdir.mkdir(anotherDirName, context.network, false, context.crypto.random).get();

        String path = "/" + username + "/" + dirName;
        FileTreeNode theDir = context.getByPath(path).get().get();
        FileTreeNode userRoot2 = context.getByPath("/" + username).get().get();
        FileTreeNode renamed = theDir.rename("subdir2", network, userRoot2).get();
    }

    // This one takes a while, so disable most of the time
//    @Test
    public void hugeFolder() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();
        List<String> names = new ArrayList<>();
        IntStream.range(0, 2000).forEach(i -> names.add(randomString()));

        for (String filename: names) {
            userRoot.mkdir(filename, context.network, false, context.crypto.random);
        }
    }

    public static void checkFileContents(byte[] expected, FileTreeNode f, UserContext context) throws Exception {
        long size = f.getFileProperties().size;
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context.network, context.crypto.random,
            size, l-> {}).get(), f.getSize()).get();
        assertEquals(expected.length, size);
        assertTrue("Correct contents", Arrays.equals(retrievedData, expected));
    }

    private static void checkFileContentsChunked(byte[] expected, FileTreeNode f, UserContext context, int  nReads) throws Exception {

        AsyncReader in = f.getInputStream(context.network, context.crypto.random,
                f.getFileProperties().size, l -> {}).get();
        assertTrue(nReads > 1);

        long size = f.getSize();
        long readLength = size/nReads;


        ByteArrayOutputStream bout = new ByteArrayOutputStream();

        for (int i = 0; i < nReads; i++) {
            long pos = i * readLength;
            long len = Math.min(readLength , expected.length - pos);
            LOG.info("Reading from "+ pos +" to "+ (pos + len) +" with total "+ expected.length);
            byte[] retrievedData = Serialize.readFully(
                    in,
                    len).get();
            bout.write(retrievedData);
        }
        byte[] readBytes = bout.toByteArray();
        assertEquals("Lengths correct", readBytes.length, expected.length);

        String start = ArrayOps.bytesToHex(Arrays.copyOfRange(expected, 0, 10));

        for (int i = 0; i < readBytes.length; i++)
            assertEquals("position  " + i + " out of " + readBytes.length + ", start of file " + start,
                    StringUtils.format("%02x", readBytes[i] & 0xFF),
                    StringUtils.format("%02x", expected[i] & 0xFF));

        assertTrue("Correct contents", Arrays.equals(readBytes, expected));
    }


    @Test
    public void readWriteTest() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        Set<FileTreeNode> children = userRoot.getChildren(context.network).get();

        children.stream()
                .map(FileTreeNode::toString)
                .forEach(System.out::println);

        String name = randomString();
        Path tmpPath = createTmpFile(name);
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining
        Files.write(tmpPath, data);

        File tmpFile = tmpPath.toFile();
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(tmpFile);
        FileTreeNode updatedRoot = userRoot.uploadFile(name, resetableFileInputStream, tmpFile.length(), context.network, context.crypto.random, (l) -> {}, context.fragmenter()).get();

        Optional<FileTreeNode> opt = updatedRoot.getChildren(context.network).get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(name))
                .findFirst();

        assertTrue("found uploaded file", opt.isPresent());

        FileTreeNode fileTreeNode = opt.get();
        long size = fileTreeNode.getFileProperties().size;
        AsyncReader in = fileTreeNode.getInputStream(context.network, context.crypto.random, size, (l) -> {}).get();
        byte[] retrievedData = Serialize.readFully(in, fileTreeNode.getSize()).get();

        boolean  dataEquals = Arrays.equals(data, retrievedData);

        assertTrue("retrieved same data", dataEquals);
    }

    @Test
    public void deleteTest() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network.clear(), crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String name = randomString();
        Path tmpPath = createTmpFile(name);
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining
        Files.write(tmpPath, data);

        File tmpFile = tmpPath.toFile();
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(tmpFile);

        FileTreeNode updatedRoot = userRoot.uploadFile(name, resetableFileInputStream, tmpFile.length(), context.network, context.crypto.random, (l) -> {}, context.fragmenter()).get();
        String otherName = name + ".other";
        FileTreeNode updatedRoot2 = updatedRoot.uploadFile(otherName, resetableFileInputStream, tmpFile.length(), context.network, context.crypto.random, (l) -> {}, context.fragmenter()).get();

        Optional<FileTreeNode> opt = updatedRoot2.getChildren(context.network).get()
                        .stream()
                        .filter(e -> e.getFileProperties().name.equals(name))
                        .findFirst();

        assertTrue("found uploaded file", opt.isPresent());

        FileTreeNode fileTreeNode = opt.get();
        long size = fileTreeNode.getFileProperties().size;
        AsyncReader in = fileTreeNode.getInputStream(context.network, context.crypto.random, size, (l) -> {}).get();
        byte[] retrievedData = Serialize.readFully(in, fileTreeNode.getSize()).get();

        boolean  dataEquals = Arrays.equals(data, retrievedData);

        assertTrue("retrieved same data", dataEquals);

        //delete the file
        fileTreeNode.remove(context.network, updatedRoot2).get();

        //re-create user-context
        UserContext context2 = ensureSignedUp(username, password, network.clear(), crypto);
        FileTreeNode userRoot2 = context2.getUserRoot().get();


        //check the file is no longer present
        boolean isPresent = userRoot2.getChildren(context2.network).get()
                .stream()
                .anyMatch(e -> e.getFileProperties().name.equals(name));

        Assert.assertFalse("uploaded file is deleted", isPresent);


        //check content of other file in same directory that was not removed
        FileTreeNode otherFileTreeNode = userRoot2.getChildren(context2.network).get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(otherName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing other file"));

        AsyncReader asyncReader = otherFileTreeNode.getInputStream(context2.network, context2.crypto.random, l -> {}).get();

        byte[] otherRetrievedData = Serialize.readFully(asyncReader, otherFileTreeNode.getSize()).get();
        boolean  otherDataEquals = Arrays.equals(data, otherRetrievedData);
        Assert.assertTrue("other file data is  intact", otherDataEquals);
    }

    @Test
    public void internalCopy() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network.clear(), crypto);
        FileTreeNode userRoot = context.getUserRoot().get();
        Path home = Paths.get(username);

        String filename = "initialfile.bin";
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining

        FileTreeNode updatedUserRoot = userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, network, crypto.random, x -> {}, context.fragmenter()).get();

        FileTreeNode original = context.getByPath(home.resolve(filename).toString()).get().get();

        // copy the file
        String foldername = "afolder";
        updatedUserRoot.mkdir(foldername, network, false, crypto.random).get();
        FileTreeNode subfolder = context.getByPath(home.resolve(foldername).toString()).get().get();
        FileTreeNode parentDir = original.copyTo(subfolder, network, crypto.random, context.fragmenter()).get();
        FileTreeNode copy = context.getByPath(home.resolve(foldername).resolve(filename).toString()).get().get();
        Assert.assertTrue("Different base key", ! copy.getPointer().filePointer.baseKey.equals(original.getPointer().filePointer.baseKey));
        Assert.assertTrue("Different metadata key", ! getMetaKey(copy).equals(getMetaKey(original)));
        Assert.assertTrue("Same data key", getDataKey(copy).equals(getDataKey(original)));
        checkFileContents(data, copy, context);
    }

    public static SymmetricKey getDataKey(FileTreeNode file) {
        return ((FileAccess)file.getPointer().fileAccess).getDataKey(file.getPointer().filePointer.baseKey);
    }

    public static SymmetricKey getMetaKey(FileTreeNode file) {
        return file.getPointer().fileAccess.getMetaKey(file.getPointer().filePointer.baseKey);
    }

    @Test
    public void deleteDirectoryTest() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        Set<FileTreeNode> children = userRoot.getChildren(context.network).get();

        children.stream()
                .map(FileTreeNode::toString)
                .forEach(System.out::println);

        String folderName = "a_folder";
        boolean isSystemFolder = false;

        //create the directory
        userRoot.mkdir(folderName, context.network, isSystemFolder, context.crypto.random).get();

        FileTreeNode updatedUserRoot = context.getUserRoot().get();
        FileTreeNode folderTreeNode = updatedUserRoot.getChildren(context.network)
                .get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(folderName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing created folder " + folderName));

        //remove the directory
        folderTreeNode.remove(context.network, updatedUserRoot).get();

        //ensure folder directory not  present
        boolean isPresent = context.getUserRoot().get().getChildren(context.network)
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

    public static byte[] randomData(int length) {
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
