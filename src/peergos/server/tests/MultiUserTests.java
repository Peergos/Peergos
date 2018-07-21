package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.storage.ResetableFileInputStream;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.server.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
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
    private final Crypto crypto;
    private final int userCount;

    public MultiUserTests(String useIPFS, Random r, int userCount, Crypto crypto) throws Exception {
        int portMin = 9000;
        int portRange = 2000;
        int webPort = portMin + r.nextInt(portRange);
        int corePort = portMin + portRange + r.nextInt(portRange);
        int socialPort = portMin + portRange + r.nextInt(portRange);
        this.userCount = userCount;
        if (userCount  < 2)
            throw new IllegalStateException();

        Args args = Args.parse(new String[]{
                "useIPFS", ""+useIPFS.equals("IPFS"),
                "-port", Integer.toString(webPort),
                "-corenodePort", Integer.toString(corePort),
                "-socialnodePort", Integer.toString(socialPort)
        });
        Start.LOCAL.main(args);
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();
        this.crypto = crypto;
    }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> parameters() {
        Random r = new Random(123);
        Crypto crypto = Crypto.initJava();
        return Arrays.asList(new Object[][] {
                {"RAM", r, 2, crypto}
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
                        return UserTests.ensureSignedUp(username, username, network.clear(), crypto);
                    } catch (Exception ioe) {
                        throw new IllegalStateException(ioe);
                    }}).collect(Collectors.toList());
    }

    @Test
    public void shareAndUnshareFile() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("a", "a", network.clear(), crypto);

        // send follow requests from each other user to "a"
        List<UserContext> userContexts = getUserContexts(userCount);
        for (UserContext userContext : userContexts) {
            userContext.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        }

        // make "a" reciprocate all the follow requests
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        for (FollowRequest u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        for (UserContext userContext : userContexts) {
            userContext.processFollowRequests().get();//needed for side effect
        }

        // upload a file to "a"'s space
        FileTreeNode u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);
        FileTreeNode uploaded = u1Root.uploadFile(filename, resetableFileInputStream, f.length(),
                u1.network, u1.crypto.random,l -> {}, u1.fragmenter()).get();

        // share the file from "a" to each of the others
        FileTreeNode u1File = u1.getByPath(u1.username + "/" + filename).get().get();
        u1.shareWith(Paths.get(u1.username, filename), userContexts.stream().map(u -> u.username).collect(Collectors.toSet())).get();

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            Optional<FileTreeNode> sharedFile = userContext.getByPath(u1.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(userContext.network,
                    userContext.crypto.random, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(originalFileContents, fileContents));
        }

        UserContext userToUnshareWith = userContexts.stream().findFirst().get();

        // unshare with a single user
        u1.unShare(Paths.get(u1.username, filename), userToUnshareWith.username).get();

        List<UserContext> updatedUserContexts = getUserContexts(userCount);

        //test that the other user cannot access it from scratch
        Optional<FileTreeNode> otherUserView = updatedUserContexts.get(0).getByPath(u1.username + "/" + filename).get();
        Assert.assertTrue(! otherUserView.isPresent());

        List<UserContext> remainingUsers = updatedUserContexts.stream()
                .skip(1)
                .collect(Collectors.toList());

        UserContext u1New = UserTests.ensureSignedUp("a", "a", network.clear(), crypto);

        // check remaining users can still read it
        for (UserContext userContext : remainingUsers) {
            String path = u1.username + "/" + filename;
            Optional<FileTreeNode> sharedFile = userContext.getByPath(path).get();
            Assert.assertTrue("path '"+ path +"' is still available", sharedFile.isPresent());
        }

        // test that u1 can still access the original file
        Optional<FileTreeNode> fileWithNewBaseKey = u1New.getByPath(u1.username + "/" + filename).get();
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file
        byte[] suffix = "Some new data at the end".getBytes();
        AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
        FileTreeNode parent = u1New.getByPath(u1New.username).get().get();
        parent.uploadFileSection(filename, suffixStream, originalFileContents.length, originalFileContents.length + suffix.length,
                Optional.empty(), true, u1New.network, u1New.crypto.random, l -> {}, u1New.fragmenter()).get();
        AsyncReader extendedContents = u1New.getByPath(u1.username + "/" + filename).get().get().getInputStream(u1New.network,
                u1New.crypto.random, l -> {}).get();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).get();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));
    }

    @Test
    public void safeCopyOfFriendsFile() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("a", "a", network.clear(), crypto);
        UserContext u2 = UserTests.ensureSignedUp("b", "b", network.clear(), crypto);

        // send follow requests from each other user to "a"
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).get();

        // make "a" reciprocate all the follow requests
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        for (FollowRequest u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        u2.processFollowRequests().get();//needed for side effect

        // upload a file to "a"'s space
        FileTreeNode u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] data = UserTests.randomData(10*1024*1024);

        FileTreeNode uploaded = u1Root.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                u1.network, u1.crypto.random,l -> {}, u1.fragmenter()).get();

        // share the file from "a" to each of the others
        FileTreeNode u1File = u1.getByPath(u1.username + "/" + filename).get().get();
        u1.shareWith(Paths.get(u1.username, filename), Collections.singleton(u2.username)).get();

        // check other user can read the file
        FileTreeNode sharedFile = u2.getByPath(u1.username + "/" + filename).get().get();
        String dirname = "adir";
        u2.getUserRoot().get().mkdir(dirname, network, false, crypto.random).get();
        FileTreeNode targetDir = u2.getByPath(Paths.get(u2.username, dirname).toString()).get().get();

        // copy the friend's file to our own space, this should reupload the file encrypted with a new key
        // this prevents us exposing to the network our social graph by the fact that we pin the same file fragments
        sharedFile.copyTo(targetDir, network, crypto.random, u2.fragmenter()).get();
        FileTreeNode copy = u2.getByPath(Paths.get(u2.username, dirname, filename).toString()).get().get();

        // check that the copied file has the correct contents
        UserTests.checkFileContents(data, copy, u2);
        Assert.assertTrue("Different base key", ! copy.getPointer().filePointer.baseKey.equals(u1File.getPointer().filePointer.baseKey));
        Assert.assertTrue("Different metadata key", ! UserTests.getMetaKey(copy).equals(UserTests.getMetaKey(u1File)));
        Assert.assertTrue("Different data key", ! UserTests.getDataKey(copy).equals(UserTests.getDataKey(u1File)));
    }

    @Ignore // until we figure out how to solve this issue
    @Test
    public void shareTwoFilesWithSameName() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("a", "a", network.clear(), crypto);

        // send follow requests from each other user to "a"
        List<UserContext> userContexts = getUserContexts(1);
        for (UserContext userContext : userContexts) {
            userContext.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        }

        // make "a" reciprocate all the follow requests
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        for (FollowRequest u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        for (UserContext userContext : userContexts) {
            userContext.processFollowRequests().get();//needed for side effect
        }

        // upload a file to "a"'s space
        FileTreeNode u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] data1 = "Hello Peergos friend!".getBytes();
        AsyncReader file1Reader = new AsyncReader.ArrayBacked(data1);
        FileTreeNode uploaded = u1Root.uploadFile(filename, file1Reader, data1.length,
                u1.network, u1.crypto.random,l -> {}, u1.fragmenter()).get();

        // upload a different file with the same name in a sub folder
        uploaded.mkdir("subdir", network, false, crypto.random).get();
        FileTreeNode subdir = u1.getByPath("/" + u1.username + "/subdir").get().get();
        byte[] data2 = "Goodbye Peergos friend!".getBytes();
        AsyncReader file2Reader = new AsyncReader.ArrayBacked(data2);
        subdir.uploadFile(filename, file2Reader, data2.length,
                u1.network, u1.crypto.random,l -> {}, u1.fragmenter()).get();

        // share the file from "a" to each of the others
        u1.shareWith(Paths.get(u1.username, filename), userContexts.stream().map(u -> u.username).collect(Collectors.toSet())).get();

        u1.shareWith(Paths.get(u1.username, "subdir", filename), userContexts.stream().map(u -> u.username).collect(Collectors.toSet())).get();

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            Optional<FileTreeNode> sharedFile = userContext.getByPath(u1.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(userContext.network,
                    userContext.crypto.random, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(data1, fileContents));
        }

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            Optional<FileTreeNode> sharedFile = userContext.getByPath(u1.username + "/" + "somefile[1].txt").get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(userContext.network,
                    userContext.crypto.random, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(data2, fileContents));
        }
    }

    @Test
    public void cleanRenamedFiles() throws Exception {
        String username = random();
        String password = random();
        UserContext u1 = UserTests.ensureSignedUp(username, password, network.clear(), crypto);

        // send follow requests from each other user to "a"
        List<UserContext> friends = getUserContexts(userCount);
        for (UserContext userContext : friends) {
            userContext.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        }

        // make "a" reciprocate all the follow requests
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        for (FollowRequest u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        for (UserContext userContext : friends) {
            userContext.processFollowRequests().get();//needed for side effect
        }

        // upload a file to "a"'s space
        FileTreeNode u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);
        FileTreeNode uploaded = u1Root.uploadFile(filename, resetableFileInputStream, f.length(),
                u1.network, u1.crypto.random,l -> {}, u1.fragmenter()).get();

        // share the file from "a" to each of the others
        String originalPath = u1.username + "/" + filename;
        FileTreeNode u1File = u1.getByPath(originalPath).get().get();
        u1.shareWith(Paths.get(u1.username, filename), friends.stream().map(u -> u.username).collect(Collectors.toSet())).get();

        // check other users can read the file
        for (UserContext friend : friends) {
            Optional<FileTreeNode> sharedFile = friend.getByPath(u1.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(friend.network,
                    friend.crypto.random, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(originalFileContents, fileContents));
        }

        UserContext userToUnshareWith = friends.stream().findFirst().get();
        String friendsPathToFile = u1.username + "/" + filename;
        Optional<FileTreeNode> priorUnsharedView = userToUnshareWith.getByPath(friendsPathToFile).get();
        FilePointer priorPointer = priorUnsharedView.get().getPointer().filePointer;
        CryptreeNode priorFileAccess = network.getMetadata(priorPointer.getLocation()).get().get();
        SymmetricKey priorMetaKey = priorFileAccess.getMetaKey(priorPointer.baseKey);

        // unshare with a single user
        u1.unShare(Paths.get(u1.username, filename), userToUnshareWith.username).get();

        String newname = "newname.txt";
        FileTreeNode updatedParent = u1.getByPath(originalPath).get().get()
                .rename(newname, network, u1.getUserRoot().get()).get();

        // check still logged in user can't read the new name
        Optional<FileTreeNode> unsharedView = userToUnshareWith.getByPath(friendsPathToFile).get();
        String friendsNewPathToFile = u1.username + "/" + newname;
        Optional<FileTreeNode> unsharedView2 = userToUnshareWith.getByPath(friendsNewPathToFile).get();
        CryptreeNode fileAccess = network.getMetadata(priorPointer.getLocation()).get().get();
        try {
            // Try decrypting the new metadata with the old key
            byte[] properties = ((CborObject.CborByteArray) ((CborObject.CborList) fileAccess.toCbor()).value.get(1)).value;
            byte[] nonce = Arrays.copyOfRange(properties, 0, TweetNaCl.SECRETBOX_NONCE_BYTES);
            byte[] cipher = Arrays.copyOfRange(properties, TweetNaCl.SECRETBOX_NONCE_BYTES, properties.length);
            FileProperties props =  FileProperties.fromCbor(CborObject.fromByteArray(priorMetaKey.decrypt(cipher, nonce)));
            throw new IllegalStateException("We shouldn't be able to decrypt this after a rename! new name = " + props.name);
        } catch (TweetNaCl.InvalidCipherTextException e) {}
        try {
            FileProperties freshProperties = fileAccess.getProperties(priorPointer.baseKey);
            throw new IllegalStateException("We shouldn't be able to decrypt this after a rename!");
        } catch (TweetNaCl.InvalidCipherTextException e) {}

        Assert.assertTrue("target can't read through original path", ! unsharedView.isPresent());
        Assert.assertTrue("target can't read through new path", ! unsharedView2.isPresent());

        List<UserContext> updatedUserContexts = getUserContexts(userCount);

        List<UserContext> remainingUsers = updatedUserContexts.stream()
                .skip(1)
                .collect(Collectors.toList());

        UserContext u1New = UserTests.ensureSignedUp(username, password, network.clear(), crypto);

        // check remaining users can still read it
        for (UserContext userContext : remainingUsers) {
            String path = u1.username + "/" + newname;
            Optional<FileTreeNode> sharedFile = userContext.getByPath(path).get();
            Assert.assertTrue("path '"+ path +"' is still available", sharedFile.isPresent());
        }

        // test that u1 can still access the original file
        Optional<FileTreeNode> fileWithNewBaseKey = u1New.getByPath(u1.username + "/" + newname).get();
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file
        byte[] suffix = "Some new data at the end".getBytes();
        AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
        FileTreeNode parent = u1New.getByPath(u1New.username).get().get();
        parent.uploadFileSection(newname, suffixStream, originalFileContents.length, originalFileContents.length + suffix.length,
                Optional.empty(), true, u1New.network, u1New.crypto.random, l -> {}, u1New.fragmenter()).get();
        AsyncReader extendedContents = u1New.getByPath(u1.username + "/" + newname).get().get()
                .getInputStream(u1New.network, u1New.crypto.random, l -> {}).get();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).get();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));
    }

    private String random() {
        return ArrayOps.bytesToHex(crypto.random.randomBytes(15));
    }

    @Test
    public void shareAndUnshareFolder() throws Exception {
        shareAndUnshareFolder(4);
    }

    public void shareAndUnshareFolder(int userCount) throws Exception {
        Assert.assertTrue(0 < userCount);

        String u1name = "a";
        String u1Password = "a";
        UserContext u1 = UserTests.ensureSignedUp(u1name, u1Password, network, crypto);
        List<UserContext> users = new ArrayList<>();
        List<String>  userNames =  new ArrayList<>(), userPasswords = new ArrayList<>();
        for (int i = 0; i < userCount; i++) {
            userNames.add(random());
            userPasswords.add(random());
        }

        for (int i = 0; i < userCount; i++)
            users.add(UserTests.ensureSignedUp(userNames.get(i), userPasswords.get(i), network, crypto));

        for (UserContext user : users)
            user.sendFollowRequest(u1.username, SymmetricKey.random()).get();

        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        for (FollowRequest u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        for (UserContext user : users) {
            user.processFollowRequests().get();
        }

        // friends are now connected
        // share a file from u1 to the others
        FileTreeNode u1Root = u1.getUserRoot().get();
        String folderName = "afolder";
        u1Root.mkdir(folderName, u1.network, SymmetricKey.random(), false, u1.crypto.random).get();
        FileTreeNode folder = u1.getByPath("/a/" + folderName).get().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(originalFileContents);
        FileTreeNode updatedFolder = folder.uploadFile(filename, resetableFileInputStream, originalFileContents.length, u1.network,
                u1.crypto.random, l -> {}, u1.fragmenter()).get();
        String originalFilePath = u1.username + "/" + folderName + "/" + filename;

        // file is uploaded, do the actual sharing
        boolean finished = u1.shareWithAll(updatedFolder, users.stream().map(c -> c.username).collect(Collectors.toSet())).get();

        // check each user can see the shared folder and directory
        for (UserContext user : users) {
            String path = u1.username;

            Optional<FileTreeNode> sharedFolder = user.getByPath(u1.username + "/" + folderName).get();
            Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getFileProperties().name.equals(folderName));

            FileTreeNode sharedFile = user.getByPath(u1.username + "/" + folderName + "/" + filename).get().get();
            AsyncReader inputStream = sharedFile.getInputStream(user.network, user.crypto.random, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.getSize()).get();

            Assert.assertTrue("shared file contents correct", Arrays.equals(originalFileContents, fileContents));
        }

        UserContext u1New = UserTests.ensureSignedUp(u1name, u1Password, network.clear(), crypto);

        List<UserContext>  usersNew = new ArrayList<>();
        for (int i = 0; i < userCount; i++)
            usersNew.add(UserTests.ensureSignedUp(userNames.get(i), userPasswords.get(i), network.clear(), crypto));

        for (int i = 0; i < usersNew.size(); i++) {
            UserContext user = users.get(i);
            u1.unShare(Paths.get(u1.username, folderName), user.username).get();

            Optional<FileTreeNode> updatedSharedFolder = user.getByPath(u1New.username + "/" + folderName).get();

            // test that u1 can still access the original file, and user cannot
            Optional<FileTreeNode> fileWithNewBaseKey = u1New.getByPath(u1New.username + "/" + folderName + "/" + filename).get();
            Assert.assertTrue(! updatedSharedFolder.isPresent());
            Assert.assertTrue(fileWithNewBaseKey.isPresent());

            // Now modify the file
            byte[] suffix = "Some new data at the end".getBytes();
            AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
            FileTreeNode parent = u1New.getByPath(u1New.username + "/" + folderName).get().get();
            parent.uploadFileSection(filename, suffixStream, originalFileContents.length, originalFileContents.length + suffix.length,
                    Optional.empty(), true, u1New.network, u1New.crypto.random, l -> {}, u1New.fragmenter()).get();
            FileTreeNode extendedFile = u1New.getByPath(originalFilePath).get().get();
            AsyncReader extendedContents = extendedFile.getInputStream(u1New.network, u1New.crypto.random, l -> {}).get();
            byte[] newFileContents = Serialize.readFully(extendedContents, extendedFile.getSize()).get();

            Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));

            // test remaining users can still see shared file and folder
            for (int j = i+1; j < usersNew.size(); j++) {
                UserContext otherUser = users.get(j);

                Optional<FileTreeNode> sharedFolder = otherUser.getByPath(u1.username + "/" + folderName).get();
                Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getName().equals(folderName));

                FileTreeNode sharedFile = otherUser.getByPath(u1.username + "/" + folderName + "/" + filename).get().get();
                AsyncReader inputStream = sharedFile.getInputStream(otherUser.network, otherUser.crypto.random, l -> {}).get();

                byte[] contents = Serialize.readFully(inputStream, sharedFile.getSize()).get();
                Assert.assertTrue(Arrays.equals(contents, newFileContents)); //remaining users share latest view of same data
            }
        }
    }

    @Test
    public void acceptAndReciprocateFollowRequest() throws Exception {
        String username1 = random();
        String password1 = random();
        UserContext u1 = UserTests.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = UserTests.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true).get();
        List<FollowRequest> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileTreeNode> u1ToU2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root present after accepted follow request", u1ToU2.isPresent());

        Optional<FileTreeNode> u2ToU1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after accepted follow request", u2ToU1.isPresent());

        Set<String> u1Following = UserTests.ensureSignedUp(username1, password1, network.clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u1Following.contains(u2.username));

        Set<String> u2Following = UserTests.ensureSignedUp(username2, password2, network.clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u2Following.contains(u1.username));
    }

    @Test
    public void followPeergos() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("peergos", "testpassword", network, crypto);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", network, crypto);

        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
    }

    @Test
    public void acceptButNotReciprocateFollowRequest() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", network, crypto);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, false).get();
        List<FollowRequest> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileTreeNode> u1Tou2 = u2.getByPath("/" + u1.username).get();
        Optional<FileTreeNode> u2Tou1 = u1.getByPath("/" + u2.username).get();

        assertTrue("Friend root present after accepted follow request", u1Tou2.isPresent());
        assertTrue("Friend root not present after non reciprocated follow request", !u2Tou1.isPresent());
    }


    @Test
    public void rejectFollowRequest() throws Exception {
        String username1 = random();
        String password1 = random();
        UserContext u1 = UserTests.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = UserTests.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), false, false);
        List<FollowRequest> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileTreeNode> u1Tou2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root not present after rejected follow request", ! u1Tou2.isPresent());

        Optional<FileTreeNode> u2Tou1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root not present after non reciprocated follow request", !u2Tou1.isPresent());
    }

    @Test
    public void acceptAndReciprocateFollowRequestThenRemoveFollowRequest() throws Exception {
        String username1 = random();
        String password1 = random();
        UserContext u1 = UserTests.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = UserTests.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true).get();
        List<FollowRequest> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileTreeNode> u1ToU2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root present after accepted follow request", u1ToU2.isPresent());

        Optional<FileTreeNode> u2ToU1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after accepted follow request", u2ToU1.isPresent());

        Set<String> u1Following = UserTests.ensureSignedUp(username1, password1, network.clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u1Following.contains(u2.username));

        Set<String> u2Following = UserTests.ensureSignedUp(username2, password2, network.clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u2Following.contains(u1.username));

        UserContext q = u1;
        UserContext w = u2;

        q.removeFollower(username2).get();

        Optional<FileTreeNode> u2ToU1Again = q.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after unfollow request", u2ToU1Again.isPresent());

        w = UserTests.ensureSignedUp(username2, password2, network, crypto);

        Optional<FileTreeNode> u1ToU2Again = w.getByPath("/" + u1.username).get();
        assertTrue("Friend root NOT present after unfollow", !u1ToU2Again.isPresent());
    }

    @Test
    public void reciprocateButNotAcceptFollowRequest() throws Exception {
        String username1 = random();
        String password1 = random();
        UserContext u1 = UserTests.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = UserTests.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), false, true);
        List<FollowRequest> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileTreeNode> u1Tou2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root not present after rejected follow request", ! u1Tou2.isPresent());

        Optional<FileTreeNode> u2Tou1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after reciprocated follow request", u2Tou1.isPresent());
    }

    @Test
    public void unfollow() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", network, crypto);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true);
        List<FollowRequest> u2FollowRequests = u2.processFollowRequests().get();

        Set<String> u1Following = u1.getFollowing().get();
        Assert.assertTrue("u1 following u2", u1Following.contains(u2.username));

        u1.unfollow(u2.username).get();

        Set<String> newU1Following = u1.getFollowing().get();
        Assert.assertTrue("u1 no longer following u2", ! newU1Following.contains(u2.username));
    }

    @Test
    public void removeFollower() throws Exception {
        UserContext u1 = UserTests.ensureSignedUp("q", "q", network, crypto);
        UserContext u2 = UserTests.ensureSignedUp("w", "w", network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true);
        List<FollowRequest> u2FollowRequests = u2.processFollowRequests().get();

        Set<String> u1Followers = u1.getFollowerNames().get();
        Assert.assertTrue("u1 following u2", u1Followers.contains(u2.username));

        u1.removeFollower(u2.username).get();

        Set<String> newU1Followers = u1.getFollowerNames().get();
        Assert.assertTrue("u1 no longer has u2 as follower", ! newU1Followers.contains(u2.username));
    }
}
