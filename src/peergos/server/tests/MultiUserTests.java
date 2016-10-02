package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
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
import java.util.stream.*;

import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class MultiUserTests {

    private final NetworkAccess network;
    private final int userCount;
    public MultiUserTests(String useIPFS, Random r, int userCount) throws Exception {
        int webPort = 9000 + r.nextInt(1000);
        int corePort = 10000 + r.nextInt(1000);
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();
        this.userCount = userCount;
        if (userCount  < 2)
            throw new IllegalStateException();

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
                {"RAM", r, 2}
        });
    }

    private static String username(int i){
        return "username_"+i;
    }

    private List<UserContext> getUserContexts(int size) {
        return IntStream.range(0, size)
                .mapToObj(e -> {
                    String username = username(e);
                    try {
                        return UserTests.ensureSignedUp(username, username, network);
                    } catch (Exception ioe) {
                        throw new IllegalStateException(ioe);
                    }}).collect(Collectors.toList());
    }

    @Test
    public void shareAndUnshareFile() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("a", "a", network);

        // send follow requests from each other user to "a"
        List<UserContext> userContexts = getUserContexts(userCount);
        for (UserContext userContext : userContexts) {
            userContext.sendFollowRequest(u1.username, SymmetricKey.random());
        }

        // make "a" reciprocate all the follow requests
        List<FollowRequest> u1Requests = u1.getFollowRequests().get();
        for (FollowRequest u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate);
        }

        // complete the friendship connection
        for (UserContext userContext : userContexts) {
            userContext.getFollowRequests();//needed for side effect
        }

        // upload a file to "a"'s space
        FileTreeNode u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        Files.write(f.toPath(), originalFileContents);
        boolean uploaded = u1Root.uploadFile(filename, f, u1, l -> {}, u1.fragmenter()).get();

        // share the file from "a" to each of the others
        FileTreeNode u1File = u1.getByPath(u1.username + "/" + filename).get().get();
        u1.share(Paths.get(u1.username, filename), userContexts.stream().map(u -> u.username).collect(Collectors.toSet()));

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            Optional<FileTreeNode> sharedFile = userContext.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME +
                    "/" + userContext.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            InputStream inputStream = sharedFile.get().getInputStream(userContext, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream);
            Assert.assertTrue("shared file contents correct", Arrays.equals(originalFileContents, fileContents));
        }

        UserContext userToUnshareWith = userContexts.stream().findFirst().get();

        // unshare with a single user
        u1.unShare(Paths.get(u1.username, filename), userToUnshareWith.username);

        List<UserContext> updatedUserContexts = getUserContexts(userCount);

        //test that the other user cannot access it from scratch
        Optional<FileTreeNode> otherUserView = updatedUserContexts.get(0).getByPath(u1.username + "/" + filename).get();
        Assert.assertTrue(! otherUserView.isPresent());

        List<UserContext> remainingUsers = updatedUserContexts.stream()
                .skip(1)
                .collect(Collectors.toList());

        UserContext u1New = UserTests.ensureSignedUp("a", "a", network);

        // check remaining users can still read it
        for (UserContext userContext : remainingUsers) {
            Optional<FileTreeNode> sharedFile = userContext.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME +
                    "/" + userContext.username + "/" + filename).get();
            Assert.assertTrue(sharedFile.isPresent());
        }

        // test that u1 can still access the original file
        Optional<FileTreeNode> fileWithNewBaseKey = u1New.getByPath(u1.username + "/" + filename).get();
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file
        byte[] suffix = "Some new data at the end".getBytes();
        InputStream suffixStream = new ByteArrayInputStream(suffix);
        FileTreeNode parent = u1New.getByPath(u1New.username).get().get();
        parent.uploadFile(filename, suffixStream, originalFileContents.length, originalFileContents.length + suffix.length,
                Optional.empty(), u1New, l -> {}, u1New.fragmenter());
        InputStream extendedContents = u1New.getByPath(u1.username + "/" + filename).get().get().getInputStream(u1New, l -> {}).get();
        byte[] newFileContents = Serialize.readFully(extendedContents);

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));
    }

    private String random() {
        return UUID.randomUUID().toString();
    }
    public void shareAndUnshareFolder(int userCount) throws Exception {
        Assert.assertTrue(0 < userCount);

        String u1nameAndPasword = "a";
        UserContext u1 = UserTests.ensureSignedUp(u1nameAndPasword, u1nameAndPasword, network);
//        UserContext u2 = UserTests.ensureSignedUp("b", "b", webPort);
        List<UserContext> users = new ArrayList<>();
        List<String>  userNames =  new ArrayList<>(), userPasswords = new ArrayList<>();
        for (int i = 0; i < userCount; i++) {
            userNames.add(random());
            userPasswords.add(random());
        }

        for (int i = 0; i < userCount; i++)
            users.add(UserTests.ensureSignedUp(userNames.get(i), userPasswords.get(i), network));

        for (UserContext user : users)
            user.sendFollowRequest(u1.username, SymmetricKey.random());


        List<FollowRequest> u1Requests = u1.getFollowRequests().get();
        for (FollowRequest u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate);
        }

        for (UserContext user : users) {
            user.getFollowRequests();
        }

        // friends are connected
        // share a file from u1 to u2
        FileTreeNode u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        Files.write(f.toPath(), originalFileContents);
        String folderName = "afolder";
        u1Root.mkdir(folderName, u1, SymmetricKey.random(), false, u1.crypto.random);
        FileTreeNode folder = u1.getByPath("/a/" + folderName).get().get();
        boolean uploaded = folder.uploadFile(filename, f, u1, l -> {}, u1.fragmenter()).get();
        String originalPath = u1.username + "/" + folderName + "/" + filename;
        FileTreeNode file = u1.getByPath(originalPath).get().get();

        byte[] fileContents = null;

        for (UserContext user : users) {
            String path = u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + user.username;
            FileTreeNode u1ToU2 = u1.getByPath(path).get().get();
            FileTreeNode fileTreeNode = u1ToU2.addLinkTo(folder, u1).get();
            FileTreeNode ownerViewOfLink = u1.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + user.username + "/" + folderName).get().get();

            Set<FileTreeNode> u2children = user
                    .getByPath(path)
                    .get().get()
                    .getChildren(user).get();
            Optional<FileTreeNode> fromParent = u2children.stream()
                    .filter(fn -> fn.getFileProperties().name.equals(folderName))
                    .findAny();
            Assert.assertTrue("shared file present via parent's children", fromParent.isPresent());


            Optional<FileTreeNode> sharedFolder = user.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + user.username + "/" + folderName).get();
            Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getFileProperties().name.equals(folderName));

            FileTreeNode sharedFile = user.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + user.username + "/" + folderName + "/" + filename).get().get();
            InputStream inputStream = sharedFile.getInputStream(user, l -> {}).get();

            byte[] contents = Serialize.readFully(inputStream);
            if (fileContents != null)
                Assert.assertTrue(
                        Arrays.equals(contents, fileContents)); //users share same view of data

            fileContents = contents;

            Assert.assertTrue("shared file contents correct", Arrays.equals(originalFileContents, fileContents));
        }

        // unshare
//        for (UserContext user : users) {
//            u1.unShare(Paths.get(u1nameAndPasword, folderName), user.username);
//        }

        //test that u2 cannot access it from scratch
        UserContext u1New = UserTests.ensureSignedUp(u1nameAndPasword, u1nameAndPasword, network);

        List<UserContext>  usersNew = new ArrayList<>();
        for (int i = 0; i < userCount; i++)
            usersNew.add(UserTests.ensureSignedUp(userNames.get(i), userPasswords.get(i), network));

        for (int i = 0; i < usersNew.size(); i++) {
            UserContext user = usersNew.get(i);
            u1.unShare(Paths.get(u1nameAndPasword, folderName), user.username);

            Optional<FileTreeNode> updatedSharedFolder = user.getByPath(u1New.username + "/" + UserContext.SHARED_DIR_NAME + "/" + user.username + "/" + folderName).get();

            // test that u1 can still access the original file
            Optional<FileTreeNode> fileWithNewBaseKey = u1New.getByPath(u1New.username + "/" + folderName + "/" + filename).get();
            Assert.assertTrue(!updatedSharedFolder.isPresent());
            Assert.assertTrue(fileWithNewBaseKey.isPresent());

            // Now modify the file
            byte[] suffix = "Some new data at the end".getBytes();
            InputStream suffixStream = new ByteArrayInputStream(suffix);
            FileTreeNode parent = u1New.getByPath(u1New.username + "/" + folderName).get().get();
            parent.uploadFile(filename, suffixStream, fileContents.length, fileContents.length + suffix.length, Optional.empty(), u1New, l -> {
            }, u1New.fragmenter());
            InputStream extendedContents = u1New.getByPath(originalPath).get().get().getInputStream(u1New, l -> {}).get();
            byte[] newFileContents = Serialize.readFully(extendedContents);

            Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(fileContents, suffix)));

            //test remaining users  can still see shared file and  folder
            for (int j = i+1; j < usersNew.size(); j++) {
                UserContext otherUser = usersNew.get(j);

                Optional<FileTreeNode> sharedFolder = otherUser.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + otherUser.username + "/" + folderName).get();
                Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getFileProperties().name.equals(folderName));

                FileTreeNode sharedFile = otherUser.getByPath(u1.username + "/" + UserContext.SHARED_DIR_NAME + "/" + otherUser.username + "/" + folderName + "/" + filename).get().get();
                InputStream inputStream = sharedFile.getInputStream(otherUser, l -> {}).get();

                byte[] contents = Serialize.readFully(inputStream);
                Assert.assertTrue(Arrays.equals(contents, newFileContents)); //remaining users share latest view of same data
            }
        }
    }

    @Test
    public void shareAndUnshareFolder() throws Exception {
        shareAndUnshareFolder(4);
    }

    @Test
    public void acceptAndReciprocateFollowRequest() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", network);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", network);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests().get();
        Optional<FileTreeNode> u1ToU2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root present after accepted follow request", u1ToU2.isPresent());

        Optional<FileTreeNode> u2ToU1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after accepted follow request", u2ToU1.isPresent());
    }

    @Test
    public void acceptButNotReciprocateFollowRequest() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", network);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", network);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, false);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests().get();
        Optional<FileTreeNode> u1Tou2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root present after accepted follow request", u1Tou2.isPresent());

        Optional<FileTreeNode> u2Tou1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root not present after non reciprocated follow request", !u2Tou1.isPresent());
    }


    @Test
    public void rejectFollowRequest() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", network);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", network);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), false, false);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests().get();
        Optional<FileTreeNode> u1Tou2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root not present after rejected follow request", ! u1Tou2.isPresent());

        Optional<FileTreeNode> u2Tou1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root not present after non reciprocated follow request", !u2Tou1.isPresent());
    }

    @Test
    public void reciprocateButNotAcceptFollowRequest() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", network);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", network);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.getFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), false, true);
        List<FollowRequest> u2FollowRequests = u2.getFollowRequests().get();
        Optional<FileTreeNode> u1Tou2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root not present after rejected follow request", ! u1Tou2.isPresent());

        Optional<FileTreeNode> u2Tou1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after reciprocated follow request", u2Tou1.isPresent());
    }
}
