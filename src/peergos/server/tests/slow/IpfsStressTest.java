package peergos.server.tests.slow;
import java.util.logging.*;

import peergos.server.tests.*;
import peergos.server.util.Args;
import peergos.server.util.Logging;

import org.junit.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.Assert.*;

public class IpfsStressTest {
	private static final Logger LOG = Logging.LOG();

    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();

    private final Random random;

    public IpfsStressTest(Random r) throws Exception {
        this.random = r;
        int portMin = 9000;
        int portRange = 4000;
        int webPort = portMin + r.nextInt(portRange);
        int corePort = portMin + portRange + r.nextInt(portRange);
        Args args = Args.parse(new String[]{"useIPFS", "true", "-port", Integer.toString(webPort), "-corenodePort", Integer.toString(corePort)});
        Main.PKI_INIT.main(args);
        this.network = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + webPort), false).get();
    }

    public static void main(String[] args) throws Exception {
        new IpfsStressTest(new Random(0)).stressTest();
    }

    private String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
    }

    @Test
    public void stressTest() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = ensureSignedUp(username, password, network, crypto);

        // first generate a random file tree
        LOG.info("Building file tree...");
        int depth = 4;
        int filesAndFolders = generateFileTree(context, PathUtil.get(username), random, depth);
        LOG.info("Built file tree with " + filesAndFolders + " files/dirs to depth " + depth);
    }

    public static int generateFileTree(UserContext context, Path root, Random rnd, int maxDepth) throws Exception {
        int files = 1 + rnd.nextInt(4);
        List<String> fileNames = randomNames(files, rnd);
        for (String filename: fileNames)
            generateFile(context, root, filename, rnd);
        if (maxDepth == 0)
            return files;

        int folders = 2 + rnd.nextInt(3);
        List<String> folderNames = randomNames(folders, rnd);
        int total = files + folders;
        for (String folderName: folderNames) {
            mkdir(context, root, folderName, rnd);
            total += generateFileTree(context, root.resolve(folderName), rnd, maxDepth - 1);
        }
        return total;
    }

    private static List<String> randomNames(int n, Random rnd) {
        return IntStream.range(0, n)
                .mapToObj(i -> randomString(rnd, 16))
                .collect(Collectors.toList());
    }

    public static void mkdir(UserContext context, Path parentPath, String name, Random rnd) throws Exception {
        context.getByPath(parentPath.toString()).get().get()
                .mkdir(name, context.network, false, context.mirrorBatId(), context.crypto).get();
    }

    public static void generateFile(UserContext context, Path parentPath, String name, Random rnd) throws Exception {
        int size = rnd.nextInt(15*1024*1024);
        FileWrapper parent = context.getByPath(parentPath.toString()).get().get();
        parent.uploadOrReplaceFile(name, new AsyncReader.ArrayBacked(randomData(rnd, size)), size,
                        context.network, context.crypto, x -> {}).get();
    }

    public static void checkFileContents(byte[] expected, FileWrapper f, UserContext context) throws Exception {
        long size = f.getFileProperties().size;
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context.network, context.crypto,
            size, l-> {}).get(), f.getSize()).get();
        assertEquals(expected.length, size);
        assertTrue("Correct contents", Arrays.equals(retrievedData, expected));
    }

    private static void checkFileContentsChunked(byte[] expected, FileWrapper f, UserContext context, int  nReads) throws Exception {

        AsyncReader in = f.getInputStream(context.network, context.crypto,
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
                    ArrayOps.byteToHex(readBytes[i] & 0xFF),
                    ArrayOps.byteToHex(expected[i] & 0xFF));

        assertTrue("Correct contents", Arrays.equals(readBytes, expected));
    }


    public static String randomString(Random r, int length) {
        return ArrayOps.bytesToHex(randomData(r, length/2));
    }

    public static byte[] randomData(Random r, int length) {
        byte[] data = new byte[length];
        r.nextBytes(data);
        return data;
    }
}
