package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.storage.ResetableFileInputStream;
import peergos.server.util.Args;
import peergos.server.util.PeergosNetworkUtils;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.TriFunction;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.server.*;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.*;

import static org.junit.Assert.assertTrue;
import static peergos.server.util.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.util.PeergosNetworkUtils.getUserContextsForNode;

public class MultiUserTests {

    private static Args args = UserTests.buildArgs();
    private Random random = new Random();
    private final NetworkAccess network;
    private static final Crypto crypto = Crypto.initJava();
    private final int userCount;

    public MultiUserTests() throws Exception {
        this.userCount = 2;
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();
    }

    @BeforeClass
    public static void init() {
        Main.PKI_INIT.main(args);
    }

    private List<UserContext> getUserContexts(int size) {
        return getUserContextsForNode(network, random, size);
    }

    @Test
    public void grantAndRevokeFileReadAccess() throws Exception {
        PeergosNetworkUtils.grantAndRevokeFileReadAccess(network, network, userCount, random);
    }

    @Test
    public void grantAndRevokeFileWriteAccess() throws Exception {
        PeergosNetworkUtils.grantAndRevokeFileWriteAccess(network, network, userCount, random);
    }

    @Test
    public void safeCopyOfFriendsReadAccess() throws Exception {
        TriFunction<UserContext, UserContext, String, CompletableFuture<Boolean>> readAccessSharingFunction =
                (u1, u2, filename) ->
        u1.shareReadAccessWith(Paths.get(u1.username, filename), Collections.singleton(u2.username));
        safeCopyOfFriends(readAccessSharingFunction);
    }

    @Test
    public void safeCopyOfFriendsWriteAccess() throws Exception {
        TriFunction<UserContext, UserContext, String, CompletableFuture<Boolean>> writeAccessSharingFunction =
                (u1, u2, filename) ->
                        u1.shareWriteAccessWith(Paths.get(u1.username, filename), Collections.singleton(u2.username));
        safeCopyOfFriends(writeAccessSharingFunction);
    }

    private void safeCopyOfFriends(TriFunction<UserContext, UserContext, String, CompletableFuture<Boolean>> sharingFunction) throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(random(), "b", network.clear(), crypto);

        // send follow requests from each other user to "a"
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).get();

        // make "a" reciprocate all the follow requests
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        for (FollowRequestWithCipherText u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        u2.processFollowRequests().get();//needed for side effect

        // upload a file to "a"'s space
        FileWrapper u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] data = UserTests.randomData(10*1024*1024);

        FileWrapper uploaded = u1Root.uploadOrOverwriteFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                u1.network, crypto.random, crypto.hasher, l -> {},
                u1Root.generateChildLocationsFromSize(data.length, u1.crypto.random)).get();

        // share the file from "a" to each of the others
        FileWrapper u1File = u1.getByPath(u1.username + "/" + filename).get().get();
        sharingFunction.apply(u1, u2, filename).get();

        // check other user can read the file
        FileWrapper sharedFile = u2.getByPath(u1.username + "/" + filename).get().get();
        String dirname = "adir";
        u2.getUserRoot().get().mkdir(dirname, network, false, crypto.random, crypto.hasher).get();
        FileWrapper targetDir = u2.getByPath(Paths.get(u2.username, dirname).toString()).get().get();

        // copy the friend's file to our own space, this should reupload the file encrypted with a new key
        // this prevents us exposing to the network our social graph by the fact that we pin the same file fragments
        sharedFile.copyTo(targetDir, network, crypto.random, crypto.hasher).get();
        FileWrapper copy = u2.getByPath(Paths.get(u2.username, dirname, filename).toString()).get().get();

        // check that the copied file has the correct contents
        UserTests.checkFileContents(data, copy, u2);
        Assert.assertTrue("Different base key", ! copy.getPointer().capability.rBaseKey.equals(u1File.getPointer().capability.rBaseKey));
        Assert.assertTrue("Different metadata key", ! UserTests.getMetaKey(copy).equals(UserTests.getMetaKey(u1File)));
        Assert.assertTrue("Different data key", ! UserTests.getDataKey(copy).equals(UserTests.getDataKey(u1File)));
    }


    @Test
    public void shareTwoFilesWithSameNameReadAccess() throws Exception {
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> readAccessSharingFunction =
                (u1, userContexts, path) ->
                        u1.shareReadAccessWith(path, userContexts.stream().map(u -> u.username).collect(Collectors.toSet()));
        shareTwoFilesWithSameName(readAccessSharingFunction);
    }

    @Test
    public void shareTwoFilesWithSameNameWriteAccess() throws Exception {
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> writeAccessSharingFunction =
                (u1, userContexts, path) ->
                        u1.shareWriteAccessWith(path, userContexts.stream().map(u -> u.username).collect(Collectors.toSet()));
        shareTwoFilesWithSameName(writeAccessSharingFunction);
    }

    private void shareTwoFilesWithSameName(TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> sharingFunction) throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        // send follow requests from each other user to "a"
        List<UserContext> userContexts = getUserContexts(1);
        for (UserContext userContext : userContexts) {
            userContext.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        }

        // make "a" reciprocate all the follow requests
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        for (FollowRequestWithCipherText u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        for (UserContext userContext : userContexts) {
            userContext.processFollowRequests().get();//needed for side effect
        }

        // upload a file to "a"'s space
        FileWrapper u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] data1 = "Hello Peergos friend!".getBytes();
        AsyncReader file1Reader = new AsyncReader.ArrayBacked(data1);
        FileWrapper uploaded = u1Root.uploadOrOverwriteFile(filename, file1Reader, data1.length,
                u1.network, u1.crypto.random, crypto.hasher, l -> {},
                u1Root.generateChildLocationsFromSize(data1.length, u1.crypto.random)).get();

        // upload a different file with the same name in a sub folder
        uploaded.mkdir("subdir", u1.network, false, crypto.random, crypto.hasher).get();
        FileWrapper subdir = u1.getByPath("/" + u1.username + "/subdir").get().get();
        byte[] data2 = "Goodbye Peergos friend!".getBytes();
        AsyncReader file2Reader = new AsyncReader.ArrayBacked(data2);
        subdir.uploadOrOverwriteFile(filename, file2Reader, data2.length,
                u1.network, u1.crypto.random, crypto.hasher, l -> {},
                u1Root.generateChildLocationsFromSize(data2.length, u1.crypto.random)).get();

        // share the file from "a" to each of the others
        //        sharingFunction.apply(u1, u2, filenameu1.shareReadAccessWith(Paths.get(u1.username, filename), userContexts.stream().map(u -> u.username).collect(Collectors.toSet())).get();

        sharingFunction.apply(u1, userContexts, Paths.get(u1.username, filename)).get();

        sharingFunction.apply(u1, userContexts, Paths.get(u1.username, "subdir", filename)).get();

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(u1.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(userContext.network,
                    userContext.crypto.random, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(data1, fileContents));
        }

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            String expectedPath = Paths.get(u1.username, "subdir", filename).toString();
            Optional<FileWrapper> sharedFile = userContext.getByPath(expectedPath).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(userContext.network,
                    userContext.crypto.random, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(data2, fileContents));
        }
    }

    @Test
    public void deleteFileSharedWithWriteAccess() throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        // send follow requests from each other user to "a"
        List<UserContext> userContexts = getUserContexts(1);
        for (UserContext userContext : userContexts) {
            userContext.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        }

        // make "a" reciprocate all the follow requests
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        for (FollowRequestWithCipherText u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        for (UserContext userContext : userContexts) {
            userContext.processFollowRequests().get();//needed for side effect
        }

        // upload a file to "a"'s space
        FileWrapper u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] data1 = "Hello Peergos friend!".getBytes();
        AsyncReader file1Reader = new AsyncReader.ArrayBacked(data1);
        String subdirName = "subdir";
        u1Root.mkdir(subdirName, u1.network, false, crypto.random, crypto.hasher).get();
        Path subdirPath = Paths.get(u1.username, subdirName);
        FileWrapper subdir = u1.getByPath(subdirPath).get().get();
        FileWrapper uploaded = subdir.uploadOrOverwriteFile(filename, file1Reader, data1.length,
                u1.network, u1.crypto.random, crypto.hasher, l -> {},
                subdir.generateChildLocationsFromSize(data1.length, u1.crypto.random)).get();

        Path filePath = Paths.get(u1.username, subdirName, filename);
        u1.shareWriteAccessWith(filePath, userContexts.stream().map(u -> u.username).collect(Collectors.toSet()));

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(filePath).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(userContext.network,
                    userContext.crypto.random, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(data1, fileContents));
        }
        //delete file
        FileWrapper theFile = u1.getByPath(filePath).get().get();
        FileWrapper parentFolder = u1.getByPath(subdirPath).get().get();
        FileWrapper metaOnlyParent = theFile.retrieveParent(u1.network).get().get();

        Assert.assertTrue("Following parent link results in read only parent",
                ! metaOnlyParent.isWritable() && ! metaOnlyParent.isReadable());

        Set<PublicKeyHash> keysOwnedByRootSigner = WriterData.getDirectOwnedKeys(theFile.owner(), parentFolder.writer(),
                u1.network.mutable, u1.network.dhtClient).join();
        Assert.assertTrue("New writer key present", keysOwnedByRootSigner.contains(theFile.writer()));

        parentFolder.remove(u1.getUserRoot().get(), u1.network, crypto.hasher).get();
        Optional<FileWrapper> removedFile = u1.getByPath(filePath).get();
        Assert.assertTrue("file removed", ! removedFile.isPresent());

        for (UserContext userContext : userContexts) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(filePath).get();
            Assert.assertTrue("shared file removed", ! sharedFile.isPresent());
        }
        Set<PublicKeyHash> updatedKeysOwnedByRootSigner = WriterData.getDirectOwnedKeys(theFile.owner(),
                parentFolder.writer(), u1.network.mutable, u1.network.dhtClient).join();
        Assert.assertTrue("New writer key not present", ! updatedKeysOwnedByRootSigner.contains(theFile.writer()));
    }

    @Test
    public void cleanRenamedFilesReadAccess() throws Exception {
        String username = random();
        String password = random();
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);

        // send follow requests from each other user to "a"
        List<UserContext> friends = getUserContexts(userCount);
        for (UserContext userContext : friends) {
            userContext.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        }

        // make "a" reciprocate all the follow requests
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        for (FollowRequestWithCipherText u1Request : u1Requests) {
            boolean accept = true;
            boolean reciprocate = true;
            u1.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        for (UserContext userContext : friends) {
            userContext.processFollowRequests().get();//needed for side effect
        }

        // upload a file to "a"'s space
        FileWrapper u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);
        FileWrapper uploaded = u1Root.uploadOrOverwriteFile(filename, resetableFileInputStream, f.length(),
                u1.network, u1.crypto.random, crypto.hasher, l -> {},
                u1Root.generateChildLocationsFromSize(originalFileContents.length, u1.crypto.random)).get();

        // share the file from "a" to each of the others
        String originalPath = u1.username + "/" + filename;
        FileWrapper u1File = u1.getByPath(originalPath).get().get();
        u1.shareReadAccessWith(Paths.get(u1.username, filename), friends.stream().map(u -> u.username).collect(Collectors.toSet()));

        // check other users can read the file
        for (UserContext friend : friends) {
            Optional<FileWrapper> sharedFile = friend.getByPath(u1.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(friend.network,
                    friend.crypto.random, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(originalFileContents, fileContents));
        }

        UserContext userToUnshareWith = friends.stream().findFirst().get();
        String friendsPathToFile = u1.username + "/" + filename;
        Optional<FileWrapper> priorUnsharedView = userToUnshareWith.getByPath(friendsPathToFile).get();
        AbsoluteCapability priorPointer = priorUnsharedView.get().getPointer().capability;
        CryptreeNode priorFileAccess = network.getMetadata(priorPointer).get().get();
        SymmetricKey priorMetaKey = priorFileAccess.getParentKey(priorPointer.rBaseKey);

        // unshare with a single user
        u1.unShareReadAccess(Paths.get(u1.username, filename), userToUnshareWith.username).join();

        String newname = "newname.txt";
        FileWrapper updatedParent = u1.getByPath(originalPath).get().get()
                .rename(newname, network, u1.getUserRoot().get(), crypto.hasher).get();

        // check still logged in user can't read the new name
        Optional<FileWrapper> unsharedView = userToUnshareWith.getByPath(friendsPathToFile).get();
        String friendsNewPathToFile = u1.username + "/" + newname;
        Optional<FileWrapper> unsharedView2 = userToUnshareWith.getByPath(friendsNewPathToFile).get();
        CryptreeNode fileAccess = network.getMetadata(priorPointer).get().get();
        // check we are trying to decrypt the correct thing
        PaddedCipherText priorPropsCipherText = (PaddedCipherText) ((CborObject.CborMap) priorFileAccess.toCbor()).get("p");
        CborObject.CborMap priorFromParent = priorPropsCipherText.decrypt(priorMetaKey, x -> (CborObject.CborMap)x);
        FileProperties priorProps = FileProperties.fromCbor(priorFromParent.get("s"));
        try {
            // Try decrypting the new metadata with the old key
            PaddedCipherText propsCipherText = (PaddedCipherText) ((CborObject.CborMap) fileAccess.toCbor()).get("p");
            CborObject.CborMap fromParent = propsCipherText.decrypt(priorMetaKey, x -> (CborObject.CborMap)x);
            FileProperties props = FileProperties.fromCbor(fromParent.get("s"));
            throw new IllegalStateException("We shouldn't be able to decrypt this after a rename! new name = " + props.name);
        } catch (TweetNaCl.InvalidCipherTextException e) {}
        try {
            FileProperties freshProperties = fileAccess.getProperties(priorPointer.rBaseKey);
            throw new IllegalStateException("We shouldn't be able to decrypt this after a rename!");
        } catch (TweetNaCl.InvalidCipherTextException e) {}

        Assert.assertTrue("target can't read through original path", ! unsharedView.isPresent());
        Assert.assertTrue("target can't read through new path", ! unsharedView2.isPresent());

        List<UserContext> updatedUserContexts = friends.stream()
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, e.username, network, crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);
                    }
                })
                .collect(Collectors.toList());

        List<UserContext> remainingUsers = updatedUserContexts.stream()
                .skip(1)
                .collect(Collectors.toList());

        UserContext u1New = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);

        // check remaining users can still read it
        for (UserContext userContext : remainingUsers) {
            String path = u1.username + "/" + newname;
            Optional<FileWrapper> sharedFile = userContext.getByPath(path).get();
            Assert.assertTrue("path '"+ path +"' is still available", sharedFile.isPresent());
        }

        // test that u1 can still access the original file
        Optional<FileWrapper> fileWithNewBaseKey = u1New.getByPath(u1.username + "/" + newname).get();
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file
        byte[] suffix = "Some new data at the end".getBytes();
        AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
        FileWrapper parent = u1New.getByPath(u1New.username).get().get();
        parent.uploadFileSection(newname, suffixStream, false, originalFileContents.length,
                originalFileContents.length + suffix.length, Optional.empty(), true,
                u1New.network, crypto.random, crypto.hasher, l -> {}, null).get();
        AsyncReader extendedContents = u1New.getByPath(u1.username + "/" + newname).get().get()
                .getInputStream(u1New.network, crypto.random, l -> {}).get();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).get();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));
    }

    private String random() {
        return ArrayOps.bytesToHex(crypto.random.randomBytes(15));
    }

    @Test
    public void grantAndRevokeDirReadAccess() throws Exception {
        PeergosNetworkUtils.grantAndRevokeDirReadAccess(network, network, 2, random);
    }

    @Test
    public void grantAndRevokeDirWriteAccess() throws Exception {
        PeergosNetworkUtils.grantAndRevokeDirWriteAccess(network, network, 2, random);
    }

    @Test
    public void shareFolderForWriteAccess() throws Exception {
        PeergosNetworkUtils.shareFolderForWriteAccess(network, network, 2, random);
    }

    @Test
    public void acceptAndReciprocateFollowRequest() throws Exception {
        String username1 = random();
        String password1 = random();
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true).get();
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileWrapper> u1ToU2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root present after accepted follow request", u1ToU2.isPresent());

        Optional<FileWrapper> u2ToU1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after accepted follow request", u2ToU1.isPresent());

        Set<FileWrapper> children = u2ToU1.get().getChildren(u2.network).get();

        assertTrue("Browse to friend root", children.isEmpty());

        SocialState u1Social = PeergosNetworkUtils.ensureSignedUp(username1, password1, network.clear(), crypto)
                .getSocialState().get();

        Set<String> u1Following = u1Social.followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u1Following.contains(u2.username));
        assertTrue("Followers correct", u1Social.followerRoots.containsKey(username2));

        SocialState u2Social = PeergosNetworkUtils.ensureSignedUp(username2, password2, network.clear(), crypto)
                .getSocialState().get();

        Set<String> u2Following = u2Social
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u2Following.contains(u1.username));
        assertTrue("Followers correct", u2Social.followerRoots.containsKey(username1));
    }

    @Test
    public void followPeergos() throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp("w", "w", network, crypto);

        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);

        // Check that user w can send feedback to the user peergos.
        String feedback = "Here's some constructive feedback!";
        CompletableFuture<Boolean> testFeedbackSubmission = u2.submitFeedback(feedback);
        assertTrue("Feedback submission was successful!", testFeedbackSubmission.get() == true);

        // Can peergos read the feedback file?
        Path filePath = Paths.get(u1.username, "feedback");
        Optional<FileWrapper> feedbackFile = u1.getByPath(filePath).get();
        assertTrue("Feedback file present", feedbackFile.isPresent());

        AsyncReader inputStream = feedbackFile
            .get()
            .getInputStream(u1.network, u1.crypto.random, l -> {})
            .get();

        byte[] fileContents = Serialize.readFully(inputStream, feedbackFile.get().getFileProperties().size).get();
        assertTrue("Feedback file contents correct", feedback.getBytes() == fileContents);
    }

    @Test
    public void acceptButNotReciprocateFollowRequest() throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), random(), network, crypto);
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(random(), random(), network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, false).get();
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileWrapper> u1Tou2 = u2.getByPath("/" + u1.username).get();
        Optional<FileWrapper> u2Tou1 = u1.getByPath("/" + u2.username).get();

        assertTrue("Friend root present after accepted follow request", u1Tou2.isPresent());
        assertTrue("Friend root not present after non reciprocated follow request", !u2Tou1.isPresent());
    }


    @Test
    public void rejectFollowRequest() throws Exception {
        String username1 = random();
        String password1 = random();
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), false, false);
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileWrapper> u1Tou2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root not present after rejected follow request", ! u1Tou2.isPresent());

        Optional<FileWrapper> u2Tou1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root not present after non reciprocated follow request", !u2Tou1.isPresent());
    }

    @Test
    public void acceptAndReciprocateFollowRequestThenRemoveFollowRequest() throws Exception {
        String username1 = random();
        String password1 = random();
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).get();
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true).get();
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileWrapper> u1ToU2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root present after accepted follow request", u1ToU2.isPresent());

        Optional<FileWrapper> u2ToU1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after accepted follow request", u2ToU1.isPresent());

        Set<String> u1Following = PeergosNetworkUtils.ensureSignedUp(username1, password1, network.clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u1Following.contains(u2.username));

        Set<String> u2Following = PeergosNetworkUtils.ensureSignedUp(username2, password2, network.clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u2Following.contains(u1.username));

        UserContext q = u1;
        UserContext w = u2;

        q.removeFollower(username2).get();

        Optional<FileWrapper> u2ToU1Again = q.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after unfollow request", u2ToU1Again.isPresent());

        w = PeergosNetworkUtils.ensureSignedUp(username2, password2, network, crypto);

        Optional<FileWrapper> u1ToU2Again = w.getByPath("/" + u1.username).get();
        assertTrue("Friend root NOT present after unfollow", !u1ToU2Again.isPresent());
    }

    @Test
    public void reciprocateButNotAcceptFollowRequest() throws Exception {
        String username1 = random();
        String password1 = random();
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), false, true);
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileWrapper> u1Tou2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root not present after rejected follow request", ! u1Tou2.isPresent());

        Optional<FileWrapper> u2Tou1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after reciprocated follow request", u2Tou1.isPresent());
    }

    @Test
    public void unfollow() throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), random(), network, crypto);
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(random(), random(), network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true);
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().get();

        Set<String> u1Following = u1.getFollowing().get();
        Assert.assertTrue("u1 following u2", u1Following.contains(u2.username));

        u1.unfollow(u2.username).get();

        Set<String> newU1Following = u1.getFollowing().get();
        Assert.assertTrue("u1 no longer following u2", ! newU1Following.contains(u2.username));
    }

    @Test
    public void removeFollower() throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), random(), network, crypto);
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(random(), random(), network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random());
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true);
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().get();

        Set<String> u1Followers = u1.getFollowerNames().get();
        Assert.assertTrue("u1 following u2", u1Followers.contains(u2.username));

        u1.removeFollower(u2.username).get();

        Set<String> newU1Followers = u1.getFollowerNames().get();
        Assert.assertTrue("u1 no longer has u2 as follower", ! newU1Followers.contains(u2.username));
    }
}
