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

public abstract class UserTests {

    public static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public UserTests(String useIPFS, Random r) throws Exception {
        int portMin = 9000;
        int portRange = 2000;
        int webPort = portMin + r.nextInt(portRange);
        int corePort = portMin + portRange + r.nextInt(portRange);
        Args args = Args.parse(new String[]{"useIPFS", ""+useIPFS.equals("IPFS"), "-port", Integer.toString(webPort), "-corenodePort", Integer.toString(corePort)});
        Start.local(args);
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();
        // use insecure random otherwise tests take ages
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));
    }

    static void setFinalStatic(Field field, Object newValue) throws Exception {
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.set(null, newValue);
    }

    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
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
            System.out.println("User gen took " + (t2 - t1) + " mS");
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
		        throw new IllegalStateException("Generated user diferent from the Javascript! \n"+userWithRoot.getUser().publicSigningKey + " != \n"+expected);
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
        checkFileContents(data, userRoot.getDescendentByPath(filename, context.network).get().get(), context);

        // write small 1 chunk file
        byte[] data2 = "This is a small amount of data".getBytes();
        userRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(data2), 0, data2.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();
        checkFileContents(data2, userRoot.getDescendentByPath(filename, context.network).get().get(), context);

        // check multiple read calls  in one chunk
        checkFileContentsChunked(data2, userRoot.getDescendentByPath(filename, context.network).get().get(), context, 3);
        // check file size
        // assertTrue("File size", data2.length == userRoot.getDescendentByPath(filename,context.network).get().get().getFileProperties().size);


        // check multiple read calls in multiple chunks
        int bigLength = Chunk.MAX_SIZE * 3;
        byte[] bigData = new byte[bigLength];
        random.nextBytes(bigData);
        userRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(bigData), 0, bigData.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();
        checkFileContentsChunked(bigData,
                userRoot.getDescendentByPath(filename, context.network).get().get(),
                context,
                5);
        assertTrue("File size", bigData.length == context.getByPath(username + "/" + filename).get().get().getFileProperties().size);

        // extend file within existing chunk
        byte[] data3 = new byte[128 * 1024];
        new Random().nextBytes(data3);
        String otherName = "other"+filename;
        userRoot.uploadFileSection(otherName, new AsyncReader.ArrayBacked(data3), 0, data3.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();
        assertTrue("File size", data3.length == context.getByPath(username + "/" + otherName).get().get().getFileProperties().size);
        checkFileContents(data3, userRoot.getDescendentByPath(otherName, context.network).get().get(), context);

        // insert data in the middle
        byte[] data4 = "some data to insert somewhere".getBytes();
        int startIndex = 100 * 1024;
        userRoot.uploadFileSection(otherName, new AsyncReader.ArrayBacked(data4), startIndex, startIndex + data4.length,
                context.network, context.crypto.random, l -> {}, context.fragmenter()).get();
        System.arraycopy(data4, 0, data3, startIndex, data4.length);
        checkFileContents(data3, userRoot.getDescendentByPath(otherName, context.network).get().get(), context);

        //rename
        String newname = "newname.txt";
        userRoot.getDescendentByPath(otherName, context.network).get().get()
                .rename(newname, context.network, userRoot).get();
        checkFileContents(data3, userRoot.getDescendentByPath(newname, context.network).get().get(), context);
        // check from the root as well
        checkFileContents(data3, context.getByPath(username + "/" + newname).get().get(), context);
        // check from a fresh log in too
        UserContext context2 = ensureSignedUp(username, password, network.clear(), crypto);
        Optional<FileTreeNode> renamed = context2.getByPath(username + "/" + newname).get();
        checkFileContents(data3, renamed.get(), context);
    }

    @Test
    public void concurrentWrites() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        // write empty file
        int concurrency = 8;
        int fileSize = 1024;
        ForkJoinPool pool = new ForkJoinPool(concurrency);
        Set<CompletableFuture<Boolean>> futs = IntStream.range(0, concurrency)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    byte[] data = randomData(fileSize);
                    String filename = i + ".bin";
                    try {
                        boolean result = userRoot.uploadFile(filename,
                                new AsyncReader.ArrayBacked(data),
                                data.length,
                                context.network, context.crypto.random, l -> {
                                },
                                context.fragmenter()
                        ).get();
                        checkFileContents(data, context.getByPath("/" + username + "/" + filename).get().get(), context);
                        System.out.println("Finished a file");
                        return true;
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }, pool)).collect(Collectors.toSet());

        boolean success = Futures.combineAll(futs).get().stream().reduce(true, (a, b) -> a && b);

        Set<FileTreeNode> files = context.getUserRoot().get().getChildren(context.network).get();
        Set<String> names = files.stream().filter(f -> ! f.getFileProperties().isHidden).map(f -> f.getName()).collect(Collectors.toSet());
        Set<String> expectedNames = IntStream.range(0, concurrency).mapToObj(i -> i + ".bin").collect(Collectors.toSet());
        Assert.assertTrue("All children present and accounted for", names.equals(expectedNames));
    }

    @Test
    public void smallFileWrite() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "small.txt";
        byte[] data = new byte[10];
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();
    }

    @Test
    public void mediumFileWrite() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[0];
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        random.nextBytes(data5);
        userRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(data5), 0, data5.length, context.network,
                context.crypto.random, l -> {} , context.fragmenter());
        checkFileContents(data5, userRoot.getDescendentByPath(filename, context.network).get().get(), context);
        assertTrue("10MiB file size", data5.length == userRoot.getDescendentByPath(filename,
                context.network).get().get().getFileProperties().size);

        // insert data in the middle of second chunk
        System.out.println("\n***** Mid 2nd chunk write test");
        byte[] dataInsert = "some data to insert somewhere else".getBytes();
        int start = 5*1024*1024 + 4*1024;
        userRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(dataInsert), start, start + dataInsert.length,
                context.network, context.crypto.random, l -> {}, context.fragmenter());
        System.arraycopy(dataInsert, 0, data5, start, dataInsert.length);
        checkFileContents(data5, userRoot.getDescendentByPath(filename, context.network).get().get(), context);

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
        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network, context.crypto.random, l -> {}, context.fragmenter());

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        random.nextBytes(data5);
        long t1 = System.currentTimeMillis();
        userRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(data5), 0, data5.length, context.network, context.crypto.random, l -> {}, context.fragmenter());
        long t2 = System.currentTimeMillis();
        System.out.println("Write time per chunk " + (t2-t1)/2 + "mS");
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
        long t1 = System.currentTimeMillis();
        String dirName = "subdir";
        userRoot.mkdir(dirName, context.network, false, context.crypto.random).get();
        FileTreeNode subdir = context.getByPath("/" + username + "/" + dirName).get().get();
        String anotherDirName = "anotherDir";
        subdir.mkdir(anotherDirName, context.network, false, context.crypto.random).get();
        FileTreeNode anotherDir = context.getByPath("/" + username + "/" + dirName + "/" + anotherDirName).get().get();
        anotherDir.uploadFileSection(filename, new AsyncReader.ArrayBacked(data), 0, data.length, context.network,
                context.crypto.random, l -> {}, context.fragmenter()).get();
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

    private static void checkFileContents(byte[] expected, FileTreeNode f, UserContext context) throws Exception {
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context.network, context.crypto.random,
                f.getFileProperties().size, l-> {}).get(), f.getSize()).get();
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
            System.out.println("Reading from "+ pos +" to "+ (pos + len) +" with total "+ expected.length);
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
        boolean b = userRoot.uploadFile(name, resetableFileInputStream, tmpFile.length(), context.network, context.crypto.random, (l) -> {}, context.fragmenter()).get();

        assertTrue("file upload", b);

        Optional<FileTreeNode> opt = userRoot.getChildren(context.network).get()
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

        boolean b = userRoot.uploadFile(name, resetableFileInputStream, tmpFile.length(), context.network, context.crypto.random, (l) -> {}, context.fragmenter()).get();
        String otherName = name + ".other";
        boolean b2 = userRoot.uploadFile(otherName, resetableFileInputStream, tmpFile.length(), context.network, context.crypto.random, (l) -> {}, context.fragmenter()).get();

        assertTrue("file upload", b);

        Optional<FileTreeNode> opt = userRoot.getChildren(context.network).get()
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
        fileTreeNode.remove(context.network, userRoot).get();

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

        userRoot.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length, network, crypto.random, x -> {}, context.fragmenter()).get();

        FileTreeNode original = context.getByPath(home.resolve(filename).toString()).get().get();

        // copy the file
        String foldername = "afolder";
        userRoot.mkdir(foldername, network, false, crypto.random).get();
        FileTreeNode subfolder = context.getByPath(home.resolve(foldername).toString()).get().get();
        FileTreeNode parentDir = original.copyTo(subfolder, network, crypto.random).get();
        FileTreeNode copy = context.getByPath(home.resolve(foldername).resolve(filename).toString()).get().get();
        Assert.assertTrue("Different base key", ! copy.getPointer().filePointer.baseKey.equals(original.getPointer().filePointer.baseKey));
        Assert.assertTrue("Different metadata key", ! getMetaKey(copy).equals(getMetaKey(original)));
        Assert.assertTrue("Same data key", getDataKey(copy).equals(getDataKey(original)));
        checkFileContents(data, copy, context);
    }

    private static SymmetricKey getDataKey(FileTreeNode file) {
        return file.getPointer().fileAccess.getDataKey(file.getPointer().filePointer.baseKey);
    }

    private static SymmetricKey getMetaKey(FileTreeNode file) {
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

        FileTreeNode folderTreeNode = userRoot.getChildren(context.network)
                .get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(folderName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing created folder " + folderName));

        //remove the directory
        folderTreeNode.remove(context.network, userRoot).get();

        //ensure folder directory not  present
        boolean isPresent = userRoot.getChildren(context.network)
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
