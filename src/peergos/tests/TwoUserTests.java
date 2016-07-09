package peergos.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.crypto.*;
import peergos.crypto.symmetric.*;
import peergos.server.*;
import peergos.user.*;
import peergos.user.fs.*;
import peergos.util.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class TwoUserTests {

    private final int webPort;
    private final int corePort;

    public TwoUserTests(String useIPFS, Random r) throws Exception {
        this.webPort = 9000 + r.nextInt(1000);
        this.corePort = 10000 + r.nextInt(1000);
        Args args = Args.parse(new String[]{"useIPFS", ""+useIPFS.equals("IPFS"), "-port", Integer.toString(webPort), "-corenodePort", Integer.toString(corePort)});
        Start.local(args);
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

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> parameters() {
        Random r = new Random(0);
        return Arrays.asList(new Object[][] {
                {"RAM", r}
        });
    }

    @Test
    public void shareAndUnshare() throws IOException {
        UserContext u1 = UserTests.ensureSignedUp("a", "a", webPort);
        UserContext u2 = UserTests.ensureSignedUp("b", "b", webPort);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests();
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests();

        // friends are connected
        // share a file from u1 to u2
        FileTreeNode u1Root = u1.getUserRoot();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        Files.write(f.toPath(), originalFileContents);
        boolean uploaded = u1Root.uploadFile(filename, f, u1, l -> {}, u1.fragmenter());
        FileTreeNode file = u1.getByPath(u1.username + "/" + filename).get();
        FileTreeNode u1ToU2 = u1.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2.username).get();
        boolean success = u1ToU2.addLinkTo(file, u1);
        FileTreeNode ownerViewOfLink = u1.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2.username + "/" + filename).get();
        Assert.assertTrue("Shared file", success);

        Set<FileTreeNode> u2children = u2
                .getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2.username)
                .get()
                .getChildren(u2);
        Optional<FileTreeNode> fromParent = u2children.stream()
                .filter(fn -> fn.getFileProperties().name.equals(filename))
                .findAny();
        Assert.assertTrue("shared file present via parent's children", fromParent.isPresent());

        Optional<FileTreeNode> sharedFile = u2.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2.username + "/" + filename);
        Assert.assertTrue("Shared file present via direct path", sharedFile.isPresent() && sharedFile.get().getFileProperties().name.equals(filename));

        InputStream inputStream = sharedFile.get().getInputStream(u2, l -> {});

        byte[] fileContents = Serialize.readFully(inputStream);
        Assert.assertTrue("shared file contents correct", Arrays.equals(originalFileContents, fileContents));

        // unshare
        u1.unShare(Paths.get("a", filename), "b");

        //test that u2 cannot access it from scratch
        UserContext u1New = UserTests.ensureSignedUp("a", "a", webPort);
        UserContext u2New = UserTests.ensureSignedUp("b", "b", webPort);

        Optional<FileTreeNode> updatedSharedFile = u2New.getByPath(u1New.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2New.username + "/" + filename);

        // test that u1 can still access the original file
        Optional<FileTreeNode> fileWithNewBaseKey = u1New.getByPath(u1New.username + "/" + filename);
        Assert.assertTrue(! updatedSharedFile.isPresent());
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file
        byte[] suffix = "Some new data at the end".getBytes();
        InputStream suffixStream = new ByteArrayInputStream(suffix);
        FileTreeNode parent = u1New.getByPath(u1New.username).get();
        parent.uploadFile(filename, suffixStream, fileContents.length, fileContents.length + suffix.length, Optional.empty(), u1New, l -> {}, u1New.fragmenter());
        InputStream extendedContents = u1New.getByPath(u1.username + "/" + filename).get().getInputStream(u1New, l -> {});
        byte[] newFileContents = Serialize.readFully(extendedContents);

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(fileContents, suffix)));
    }

    @Test
    public void shareAndUnshareFolder() throws IOException {
        UserContext u1 = UserTests.ensureSignedUp("a", "a", webPort);
        UserContext u2 = UserTests.ensureSignedUp("b", "b", webPort);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests();
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests();

        // friends are connected
        // share a file from u1 to u2
        FileTreeNode u1Root = u1.getUserRoot();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        Files.write(f.toPath(), originalFileContents);
        String folderName = "afolder";
        u1Root.mkdir(folderName, u1, SymmetricKey.random(), false, u1.random);
        FileTreeNode folder = u1.getByPath("/a/" + folderName).get();
        boolean uploaded = folder.uploadFile(filename, f, u1, l -> {}, u1.fragmenter());
        FileTreeNode file = u1.getByPath(u1.username + "/" + folderName + "/" + filename).get();
        FileTreeNode u1ToU2 = u1.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2.username).get();
        boolean success = u1ToU2.addLinkTo(folder, u1);
        FileTreeNode ownerViewOfLink = u1.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2.username + "/" + folderName).get();
        Assert.assertTrue("Shared file", success);

        Set<FileTreeNode> u2children = u2
                .getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2.username)
                .get()
                .getChildren(u2);
        Optional<FileTreeNode> fromParent = u2children.stream()
                .filter(fn -> fn.getFileProperties().name.equals(folderName))
                .findAny();
        Assert.assertTrue("shared file present via parent's children", fromParent.isPresent());

        Optional<FileTreeNode> sharedFolder = u2.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2.username + "/" + folderName);
        Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getFileProperties().name.equals(folderName));

        FileTreeNode sharedFile = u2.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2.username + "/" + folderName + "/" + filename).get();
        InputStream inputStream = sharedFile.getInputStream(u2, l -> {});

        byte[] fileContents = Serialize.readFully(inputStream);
        Assert.assertTrue("shared file contents correct", Arrays.equals(originalFileContents, fileContents));

        // unshare
        u1.unShare(Paths.get("a", folderName), "b");

        //test that u2 cannot access it from scratch
        UserContext u1New = UserTests.ensureSignedUp("a", "a", webPort);
        UserContext u2New = UserTests.ensureSignedUp("b", "b", webPort);

        Optional<FileTreeNode> updatedSharedFolder = u2New.getByPath(u1New.username + "/" + UserContext.SHARED_DIR_NAME + "/" + u2New.username + "/" + folderName);

        // test that u1 can still access the original file
        Optional<FileTreeNode> fileWithNewBaseKey = u1New.getByPath(u1New.username + "/" + folderName + "/" + filename);
        Assert.assertTrue(! updatedSharedFolder.isPresent());
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file
        byte[] suffix = "Some new data at the end".getBytes();
        InputStream suffixStream = new ByteArrayInputStream(suffix);
        FileTreeNode parent = u1New.getByPath(u1New.username+"/"+folderName).get();
        parent.uploadFile(filename, suffixStream, fileContents.length, fileContents.length + suffix.length, Optional.empty(), u1New, l -> {}, u1New.fragmenter());
        InputStream extendedContents = u1New.getByPath(u1.username + "/" + folderName + "/" + filename).get().getInputStream(u1New, l -> {});
        byte[] newFileContents = Serialize.readFully(extendedContents);

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(fileContents, suffix)));
    }

    @Test
    public void acceptAndReciprocateFollowRequest() throws IOException {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", webPort);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", webPort);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests();
        Optional<FileTreeNode> u1ToU2 = u2.getByPath("/" + u1.username);
        assertTrue("Friend root present after accepted follow request", u1ToU2.isPresent());

        Optional<FileTreeNode> u2ToU1 = u1.getByPath("/" + u2.username);
        assertTrue("Friend root present after accepted follow request", u2ToU1.isPresent());
    }

    @Test
    public void acceptButNotReciprocateFollowRequest() throws IOException {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", webPort);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", webPort);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, false);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests();
        Optional<FileTreeNode> u1Tou2 = u2.getByPath("/" + u1.username);
        assertTrue("Friend root present after accepted follow request", u1Tou2.isPresent());

        Optional<FileTreeNode> u2Tou1 = u1.getByPath("/" + u2.username);
        assertTrue("Friend root not present after non reciprocated follow request", !u2Tou1.isPresent());
    }


    @Test
    public void rejectFollowRequest() throws IOException {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", webPort);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", webPort);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), false, false);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests();
        Optional<FileTreeNode> u1Tou2 = u2.getByPath("/" + u1.username);
        assertTrue("Friend root not present after rejected follow request", ! u1Tou2.isPresent());

        Optional<FileTreeNode> u2Tou1 = u1.getByPath("/" + u2.username);
        assertTrue("Friend root not present after non reciprocated follow request", !u2Tou1.isPresent());
    }

    @Test
    public void reciprocateButNotAcceptFollowRequest() throws IOException {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", webPort);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", webPort);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), false, true);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests();
        Optional<FileTreeNode> u1Tou2 = u2.getByPath("/" + u1.username);
        assertTrue("Friend root not present after rejected follow request", ! u1Tou2.isPresent());

        Optional<FileTreeNode> u2Tou1 = u1.getByPath("/" + u2.username);
        assertTrue("Friend root present after reciprocated follow request", u2Tou1.isPresent());
    }
}
