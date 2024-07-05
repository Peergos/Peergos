package peergos.server.tests;

import org.junit.Assert;
import peergos.server.*;
import peergos.server.apps.email.*;
import peergos.server.storage.ResetableFileInputStream;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.display.*;
import peergos.shared.email.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.messaging.*;
import peergos.shared.messaging.messages.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PeergosNetworkUtils {

    public static String generateUsername(Random random) {
        return "username-" + Math.abs(random.nextInt() % 1_000_000_000);
    }

    public static String generatePassword() {
        return ArrayOps.bytesToHex(crypto.random.randomBytes(32));
    }

    public static final Crypto crypto = Main.initCrypto();
    public static final Hasher hasher = crypto.hasher;

    public static String randomString() {
        return UUID.randomUUID().toString();
    }

    public static byte[] randomData(Random random, int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    public static String randomUsername(String prefix, Random rnd) {
        byte[] suffix = new byte[(30 - prefix.length()) / 2];
        rnd.nextBytes(suffix);
        return prefix + ArrayOps.bytesToHex(suffix);
    }

    public static void checkFileContents(byte[] expected, FileWrapper f, UserContext context) {
        long size = f.getFileProperties().size;
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context.network, context.crypto,
                size, l -> {}).join(), f.getSize()).join();
        assertEquals(expected.length, size);
        assertTrue("Correct contents", Arrays.equals(retrievedData, expected));
    }

    public static List<UserContext> getUserContextsForNode(NetworkAccess network, Random random, int size, List<String> passwords) {
        return IntStream.range(0, size)
                .mapToObj(e -> {
                    String username = generateUsername(random);
                    String password = passwords.get(e);
                    try {
                        return ensureSignedUp(username, password, network.clear(), crypto);
                    } catch (Exception ioe) {
                        throw new IllegalStateException(ioe);
                    }
                }).collect(Collectors.toList());
    }

    public static void copyDirFromFriend(NetworkAccess network, Random random) {

        String sharerUsername = generateUsername(random);
        String sharerPassword = generatePassword();
        UserContext sharerUser = ensureSignedUp(sharerUsername, sharerPassword, network, crypto);

        //sign up some users on shareeNode
        String shareeUsername = generateUsername(random);
        String shareePassword = generatePassword();
        UserContext shareeUser = ensureSignedUp(shareeUsername, shareePassword, network, crypto);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharerUser), Arrays.asList(shareeUser));

        // upload a file to "a"'s space
        FileWrapper u1Root = sharerUser.getUserRoot().join();
        String folderName = "folder";
        u1Root.mkdir(folderName, network, false, u1Root.mirrorBatId(), crypto).join();
        byte[] data = "Some text".getBytes();
        String filename = "Afile.txt";
        sharerUser.getByPath(PathUtil.get(sharerUsername, folderName)).join().get()
                .uploadOrReplaceFile(filename, AsyncReader.build(data), data.length, sharerUser.network, crypto,
                        x -> {}).join();
        String subdirName = "subdir";
        sharerUser.getByPath(PathUtil.get(sharerUsername, folderName)).join().get()
                .mkdir(subdirName, sharerUser.network, false, sharerUser.mirrorBatId(), crypto).join();

        // share
        Set<String> shareeNames = new HashSet();
        shareeNames.add(shareeUser.username);
        sharerUser.shareReadAccessWith(PathUtil.get(sharerUser.username, folderName), shareeNames).join();

        Optional<FileWrapper> sharedFile = shareeUser.getByPath(sharerUser.username + "/" + folderName).join();
        Assert.assertTrue("shared folder present", sharedFile.isPresent());
        Assert.assertTrue("Folder is read only", !sharedFile.get().isWritable());

        Optional<FileWrapper> destFolder = shareeUser.getByPath(shareeUser.username).join();
        sharedFile.get().copyTo(destFolder.get(), shareeUser).join();
        //Assert.assertTrue("Folder not copied", res);
        Optional<FileWrapper> foundFolder = shareeUser.getByPath(shareeUser.username + "/" + folderName).join();
        Assert.assertTrue("Folder accessible", foundFolder.isPresent());

        Set<FileWrapper> receivedChildren = foundFolder.get().getChildren(crypto.hasher, shareeUser.network).join();
        Assert.assertTrue(receivedChildren.stream().map(FileWrapper::getName).collect(Collectors.toSet()).equals(Set.of(filename, subdirName)));
    }

    public static void copySubdirFromFriend(NetworkAccess network, Random random) {

        String sharerUsername = generateUsername(random);
        String sharerPassword = generatePassword();
        UserContext sharerUser = ensureSignedUp(sharerUsername, sharerPassword, network, crypto);

        //sign up some users on shareeNode
        String shareeUsername = generateUsername(random);
        String shareePassword = generatePassword();
        UserContext shareeUser = ensureSignedUp(shareeUsername, shareePassword, network, crypto);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharerUser), Arrays.asList(shareeUser));

        // upload a file to /a/folder/subdir/file.txt
        FileWrapper u1Root = sharerUser.getUserRoot().join();
        String folderName = "folder";
        u1Root.mkdir(folderName, network, false, u1Root.mirrorBatId(), crypto).join();
        String subdirName = "subdir";
        sharerUser.getByPath(PathUtil.get(sharerUsername, folderName)).join().get()
                .mkdir(subdirName, sharerUser.network, false, sharerUser.mirrorBatId(), crypto).join();
        byte[] data = "Some text".getBytes();
        String filename = "file.txt";
        sharerUser.getByPath(PathUtil.get(sharerUsername, folderName, subdirName)).join().get()
                .uploadOrReplaceFile(filename, AsyncReader.build(data), data.length, sharerUser.network, crypto,
                        x -> {}).join();

        // share
        Set<String> shareeNames = new HashSet<>();
        shareeNames.add(shareeUser.username);
        sharerUser.shareReadAccessWith(PathUtil.get(sharerUser.username, folderName), shareeNames).join();

        Path subFolder = PathUtil.get(sharerUser.username, folderName, subdirName);
        Optional<FileWrapper> sharedFile = shareeUser.getByPath(sharerUser.username + "/" + folderName + "/").join().get()
                .getChildren(crypto.hasher, sharerUser.network).join().stream().findAny();
        Assert.assertTrue("shared subfolder present", sharedFile.isPresent());
        Assert.assertTrue("Folder is read only", !sharedFile.get().isWritable());

        Optional<FileWrapper> destFolder = shareeUser.getByPath(shareeUser.username).join();
        sharedFile.get().copyTo(destFolder.get(), shareeUser).join();
        Optional<FileWrapper> foundFolder = shareeUser.getByPath(shareeUser.username + "/" + subdirName).join();
        Assert.assertTrue("Folder accessible", foundFolder.isPresent());

        Set<FileWrapper> receivedChildren = foundFolder.get().getChildren(crypto.hasher, shareeUser.network).join();
        Assert.assertTrue(receivedChildren.stream().map(FileWrapper::getName).collect(Collectors.toSet()).equals(Set.of(filename)));
    }

    public static void grantAndRevokeFileReadAccess(NetworkAccess sharerNode, NetworkAccess shareeNode, int shareeCount, Random random) throws Exception {
        Assert.assertTrue(0 < shareeCount);
        //sign up a user on sharerNode

        String sharerUsername = generateUsername(random);
        String sharerPassword = generatePassword();
        UserContext sharerUser = ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        //sign up some users on shareeNode
        List<String> shareePasswords = IntStream.range(0, shareeCount)
                .mapToObj(i -> generatePassword())
                .collect(Collectors.toList());
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount, shareePasswords);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharerUser), shareeUsers);

        // upload a file to "a"'s space
        FileWrapper u1Root = sharerUser.getUserRoot().join();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = new byte[10*1024*1024];
        random.nextBytes(originalFileContents);
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);
        FileWrapper uploaded = u1Root.uploadOrReplaceFile(filename, resetableFileInputStream, f.length(),
                sharerUser.network, crypto, l -> {}).join();
        Optional<Bat> originalBat = uploaded.writableFilePointer().bat;

        // create a secret link to the file
        String userLinkPassword = "forbob";
        LinkProperties link = sharerUser.createSecretLink(Paths.get(sharerUser.username, filename).toString(), false, Optional.empty(), Optional.empty(), userLinkPassword).join();

        // share the file from sharer to each of the sharees
        Set<String> shareeNames = shareeUsers.stream()
                .map(u -> u.username)
                .collect(Collectors.toSet());
        sharerUser.shareReadAccessWith(PathUtil.get(sharerUser.username, filename), shareeNames).join();

        // check other users can read the file
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(sharerUser.username + "/" + filename).join();
            Assert.assertTrue("shared file present", sharedFile.isPresent());
            Assert.assertTrue("File is read only", ! sharedFile.get().isWritable());
            checkFileContents(originalFileContents, sharedFile.get(), userContext);
        }

        // check secret link works
        UserContext.fromSecretLinkV2(link.toLinkString(uploaded.owner()), () -> Futures.of(userLinkPassword), shareeNode, crypto).join();

        // check other users can browse to the friend's root
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> friendRoot = userContext.getByPath(sharerUser.username).join();
            assertTrue("friend root present", friendRoot.isPresent());
            Set<FileWrapper> children = friendRoot.get().getChildren(crypto.hasher, userContext.network).join();
            Optional<FileWrapper> sharedFile = children.stream()
                    .filter(file -> file.getName().equals(filename))
                    .findAny();
            assertTrue("Shared file present via root.getChildren()", sharedFile.isPresent());
        }

        UserContext userToUnshareWith = shareeUsers.stream().findFirst().get();

        // unshare with a single user
        sharerUser.unShareReadAccess(PathUtil.get(sharerUser.username, filename), userToUnshareWith.username).join();

        List<UserContext> updatedShareeUsers = shareeUsers.stream()
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, shareePasswords.get(shareeUsers.indexOf(e)), shareeNode.clear(), crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);

                    }
                }).collect(Collectors.toList());

        // check secret link works
        UserContext.fromSecretLinkV2(link.toLinkString(uploaded.owner()), () -> Futures.of(userLinkPassword), shareeNode, crypto).join();

        //test that the other user cannot access it from scratch
        Optional<FileWrapper> otherUserView = updatedShareeUsers.get(0).getByPath(sharerUser.username + "/" + filename).join();
        Assert.assertTrue(!otherUserView.isPresent());

        List<UserContext> remainingUsers = updatedShareeUsers.stream()
                .skip(1)
                .collect(Collectors.toList());

        UserContext updatedSharerUser = ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        // check remaining users can still read it
        for (UserContext userContext : remainingUsers) {
            String path = sharerUser.username + "/" + filename;
            Optional<FileWrapper> sharedFile = userContext.getByPath(path).join();
            Assert.assertTrue("path '" + path + "' is still available", sharedFile.isPresent());
            checkFileContents(originalFileContents, sharedFile.get(), userContext);
            Optional<Bat> newBat = sharedFile.get().readOnlyPointer().bat;
            Assert.assertTrue(! newBat.equals(originalBat));
        }

        // test that u1 can still access the original file
        Optional<FileWrapper> fileWithNewBaseKey = updatedSharerUser.getByPath(sharerUser.username + "/" + filename).join();
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file
        byte[] suffix = "Some new data at the end".getBytes();
        AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
        FileWrapper parent = updatedSharerUser.getByPath(updatedSharerUser.username).join().get();
        parent.uploadFileSection(filename, suffixStream, false, originalFileContents.length, originalFileContents.length + suffix.length,
                Optional.empty(), true, updatedSharerUser.network, crypto, l -> {},
                null, Optional.empty(), null, parent.mirrorBatId()).join();
        AsyncReader extendedContents = updatedSharerUser.getByPath(sharerUser.username + "/" + filename).join().get()
                .getInputStream(updatedSharerUser.network, crypto, l -> {}).join();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).join();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));
    }

    public static void socialFeedCommentOnSharedFile(NetworkAccess sharerNode, NetworkAccess shareeNode, Random random) throws Exception {
        //sign up a user on sharerNode
        String sharerUsername = randomUsername("sharer-", random);
        String sharerPassword = generatePassword();
        UserContext sharer = ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        //sign up some users on shareeNode
        int shareeCount = 1;
        List<String> shareePasswords = IntStream.range(0, shareeCount)
                .mapToObj(i -> generatePassword())
                .collect(Collectors.toList());
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount, shareePasswords);
        UserContext sharee = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // upload a file to "a"'s space
        FileWrapper u1Root = sharer.getUserRoot().join();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = new byte[10*1024*1024];
        random.nextBytes(originalFileContents);
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);
        FileWrapper uploaded = u1Root.uploadOrReplaceFile(filename, resetableFileInputStream, f.length(),
                sharer.network, crypto, l -> {}).join();

        // share the file from sharer to each of the sharees
        Set<String> shareeNames = shareeUsers.stream()
                .map(u -> u.username)
                .collect(Collectors.toSet());
        sharer.shareReadAccessWith(PathUtil.get(sharer.username, filename), shareeNames).join();

        SocialFeed receiverFeed = sharee.getSocialFeed().join().update().join();
        List<Pair<SharedItem, FileWrapper>> files = receiverFeed.getSharedFiles(0, 100).join();
        assertTrue(files.size() == 3);
        FileWrapper sharedFile = files.get(files.size() -1).right;
        SharedItem sharedItem = files.get(files.size() -1).left;

        Multihash hash = sharedFile.getContentHash(sharee.network, sharee.crypto).join();
        String replyText = "reply";
        SocialPost.Resharing resharingType = SocialPost.Resharing.Friends;
        FileRef parent = new FileRef(sharedItem.path, sharedItem.cap, hash);
        SocialPost replySocialPost = SocialPost.createComment(parent, resharingType, sharee.username,
                Arrays.asList(new Text(replyText)));
        Pair<Path, FileWrapper> result = receiverFeed.createNewPost(replySocialPost).join();
        String friendGroup = SocialState.FRIENDS_GROUP_NAME;
        String receiverGroupUid = sharee.getSocialState().join().groupNameToUid.get(friendGroup);
        sharee.shareReadAccessWith(result.left, Set.of(receiverGroupUid)).join();

        //now sharer should see the reply
        SocialFeed feed = sharer.getSocialFeed().join().update().join();
        files = feed.getSharedFiles(0, 100).join();
        //assertTrue(files.size() == 5);

    }

    public static void socialFeedCASExceptionOnUpdate(NetworkAccess sharerNode, NetworkAccess shareeNode, Random random) {
        //sign up a user on sharerNode
        String sharerUsername = randomUsername("sharer-", random);
        String sharerPassword = generatePassword();
        UserContext sharer = ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        //sign up some users on shareeNode
        int shareeCount = 1;
        List<String> shareePasswords = IntStream.range(0, shareeCount)
                .mapToObj(i -> generatePassword())
                .collect(Collectors.toList());
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount, shareePasswords);
        UserContext sharee = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        SocialFeed senderFeed = sharer.getSocialFeed().join().update().join();
        List<peergos.shared.display.Text> body = new ArrayList<>();
        body.add(new peergos.shared.display.Text("msg!"));
        SocialPost socialPost = peergos.shared.social.SocialPost.createInitialPost(sharerUsername, body, SocialPost.Resharing.Friends);

        Pair<Path, FileWrapper> result = senderFeed.createNewPost(socialPost).join();
        Set<String> readers = Set.of(sharee.username);
        sharer.shareReadAccessWith(result.left, readers).join();

        int startIndex = senderFeed.getLastSeenIndex();
        SocialFeed updatedSenderFeed = senderFeed.update().join();

        int requestSize = 100;
        List<SharedItem> items = updatedSenderFeed.getShared(startIndex, startIndex + requestSize, sharer.crypto, sharer.network).join();

        int newIndex = startIndex + items.size();
        updatedSenderFeed.setLastSeenIndex(newIndex).join();
    }
    public static void grantAndRevokeFileWriteAccess(NetworkAccess sharerNode, NetworkAccess shareeNode, int shareeCount, Random random) throws Exception {
        Assert.assertTrue(0 < shareeCount);
        //sign up a user on sharerNode

        String sharerUsername = generateUsername(random);
        String sharerPassword = generatePassword();
        UserContext sharerUser = ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        //sign up some users on shareeNode
        List<String> shareePasswords = IntStream.range(0, shareeCount)
                .mapToObj(i -> generatePassword())
                .collect(Collectors.toList());
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount, shareePasswords);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharerUser), shareeUsers);

        // upload a file to "a"'s space
        FileWrapper u1Root = sharerUser.getUserRoot().join();
        String filename = "somefile.txt";
        byte[] originalFileContents = new byte[10*1024*1024];
        random.nextBytes(originalFileContents);
        AsyncReader resetableFileInputStream = AsyncReader.build(originalFileContents);
        FileWrapper uploaded = u1Root.uploadOrReplaceFile(filename, resetableFileInputStream, originalFileContents.length,
                sharerUser.network, crypto, l -> {}).join();

        // share the file from sharer to each of the sharees
        String filePath = sharerUser.username + "/" + filename;
        FileWrapper u1File = sharerUser.getByPath(filePath).join().get();
        byte[] originalStreamSecret = u1File.getFileProperties().streamSecret.get();
        sharerUser.shareWriteAccessWith(PathUtil.get(sharerUser.username, filename), shareeUsers.stream().map(u -> u.username).collect(Collectors.toSet())).join();

        // check other users can read the file
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(filePath).join();
            Assert.assertTrue("shared file present", sharedFile.isPresent());
            Assert.assertTrue("File is writable", sharedFile.get().isWritable());
            checkFileContents(originalFileContents, sharedFile.get(), userContext);
            // check the other user can't rename the file
            FileWrapper parent = userContext.getByPath(sharerUser.username).join().get();
            CompletableFuture<FileWrapper> rename = sharedFile.get()
                    .rename("Somenew name.dat", parent, PathUtil.get(filePath), userContext);
            assertTrue("Cannot rename", rename.isCompletedExceptionally());
        }

        // check other users can browser to the friend's root
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> friendRoot = userContext.getByPath(sharerUser.username).join();
            assertTrue("friend root present", friendRoot.isPresent());
            Set<FileWrapper> children = friendRoot.get().getChildren(crypto.hasher, userContext.network).join();
            Optional<FileWrapper> sharedFile = children.stream()
                    .filter(file -> file.getName().equals(filename))
                    .findAny();
            assertTrue("Shared file present via root.getChildren()", sharedFile.isPresent());
        }
        MultiUserTests.checkUserValidity(sharerNode, sharerUsername);

        UserContext userToUnshareWith = shareeUsers.stream().findFirst().get();

        // unshare with a single user
        sharerUser.unShareWriteAccess(PathUtil.get(sharerUser.username, filename), userToUnshareWith.username).join();

        List<UserContext> updatedShareeUsers = shareeUsers.stream()
                .map(e -> ensureSignedUp(e.username, shareePasswords.get(shareeUsers.indexOf(e)), shareeNode, crypto))
                .collect(Collectors.toList());

        //test that the other user cannot access it from scratch
        Optional<FileWrapper> otherUserView = updatedShareeUsers.get(0).getByPath(filePath).join();
        Assert.assertTrue(!otherUserView.isPresent());

        List<UserContext> remainingUsers = updatedShareeUsers.stream()
                .skip(1)
                .collect(Collectors.toList());

        UserContext updatedSharerUser = ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        FileWrapper theFile = updatedSharerUser.getByPath(filePath).join().get();
        byte[] newStreamSecret = theFile.getFileProperties().streamSecret.get();
        boolean sameStreams = Arrays.equals(originalStreamSecret, newStreamSecret);
        Assert.assertTrue("Stream secret should change on revocation", ! sameStreams);

        String retrievedPath = theFile.getPath(sharerNode).join();
        Assert.assertTrue("File has correct path", retrievedPath.equals("/" + filePath));

        // check remaining users can still read it
        for (UserContext userContext : remainingUsers) {
            String path = filePath;
            Optional<FileWrapper> sharedFile = userContext.getByPath(path).join();
            Assert.assertTrue("path '" + path + "' is still available", sharedFile.isPresent());
            checkFileContents(originalFileContents, sharedFile.get(), userContext);
        }

        // test that u1 can still access the original file
        Optional<FileWrapper> fileWithNewBaseKey = updatedSharerUser.getByPath(filePath).join();
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file from the sharer
        byte[] suffix = "Some new data at the end".getBytes();
        AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
        FileWrapper parent = updatedSharerUser.getByPath(updatedSharerUser.username).join().get();
        parent.uploadFileSection(filename, suffixStream, false, originalFileContents.length, originalFileContents.length + suffix.length,
                Optional.empty(), true, updatedSharerUser.network, crypto, l -> {},
                null, Optional.empty(), null, parent.mirrorBatId()).join();
        AsyncReader extendedContents = updatedSharerUser.getByPath(filePath).join().get().getInputStream(updatedSharerUser.network,
                updatedSharerUser.crypto, l -> {}).join();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).join();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));

        // Now modify the file from the sharee
        byte[] suffix2 = "Some more data".getBytes();
        AsyncReader suffixStream2 = new AsyncReader.ArrayBacked(suffix2);
        UserContext sharee = remainingUsers.get(0);
        FileWrapper parent2 = sharee.getByPath(updatedSharerUser.username).join().get();
        parent2.uploadFileSection(filename, suffixStream2, false,
                originalFileContents.length + suffix.length,
                originalFileContents.length + suffix.length + suffix2.length,
                Optional.empty(), true, shareeNode, crypto, l -> {},
                null, Optional.empty(), null, parent.mirrorBatId()).join();
        AsyncReader extendedContents2 = sharee.getByPath(filePath).join().get()
                .getInputStream(updatedSharerUser.network,
                updatedSharerUser.crypto, l -> {}).join();
        byte[] newFileContents2 = Serialize.readFully(extendedContents2,
                originalFileContents.length + suffix.length + suffix2.length).join();

        byte[] expected = ArrayOps.concat(ArrayOps.concat(originalFileContents, suffix), suffix2);
        equalArrays(newFileContents2, expected);
        MultiUserTests.checkUserValidity(sharerNode, sharerUsername);
    }

    public static void equalArrays(byte[] a, byte[] b) {
        if (a.length != b.length)
            throw new IllegalStateException("Different length arrays!");
        for (int i=0; i < a.length; i++)
            if (a[i] != b[i])
                throw new IllegalStateException("Different at index " + i);
    }

    public static void shareFileWithDifferentSigner(NetworkAccess sharerNode,
                                                    NetworkAccess shareeNode,
                                                    Random random) {
        // sign up the sharer
        String sharerUsername = generateUsername(random);
        String sharerPassword = generatePassword();
        UserContext sharer = ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        // sign up the sharee
        String shareeUsername = generateUsername(random);
        String shareePassword = generatePassword();
        UserContext sharee = ensureSignedUp(shareeUsername, shareePassword, shareeNode.clear(), crypto);

        // friend users
        friendBetweenGroups(Arrays.asList(sharer), Arrays.asList(sharee));

        // make directory /sharer/dir and grant write access to it to a friend
        String dirName = "dir";
        sharer.getUserRoot().join().mkdir(dirName, sharer.network, false, sharer.mirrorBatId(), crypto).join();
        Path dirPath = PathUtil.get(sharerUsername, dirName);
        sharer.shareWriteAccessWith(dirPath, Collections.singleton(sharee.username)).join();

        // no revoke write access to dir
        sharer.unShareWriteAccess(dirPath, sharee.username).join();

        // check sharee can't read the dir
        Optional<FileWrapper> sharedDir = sharee.getByPath(dirPath).join();
        Assert.assertTrue("unshared dir not present", ! sharedDir.isPresent());

        // upload a file to the dir
        FileWrapper dir = sharer.getByPath(dirPath).join().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = new byte[10*1024*1024];
        random.nextBytes(originalFileContents);
        AsyncReader resetableFileInputStream = AsyncReader.build(originalFileContents);
        FileWrapper uploaded = dir.uploadOrReplaceFile(filename, resetableFileInputStream, originalFileContents.length,
                sharer.network, crypto, l -> {}).join();

        // share the file read only with the sharee
        Path filePath = dirPath.resolve(filename);
        FileWrapper u1File = sharer.getByPath(filePath).join().get();
        sharer.shareWriteAccessWith(filePath, Collections.singleton(sharee.username)).join();

        // check other user can read the file directly
        Optional<FileWrapper> sharedFile = sharee.getByPath(filePath).join();
        Assert.assertTrue("shared file present", sharedFile.isPresent());
        checkFileContents(originalFileContents, sharedFile.get(), sharee);
        // check other user can read the file via its parent
        Optional<FileWrapper> sharedDirViaFile = sharee.getByPath(dirPath.toString()).join();
        Set<FileWrapper> children = sharedDirViaFile.get().getChildren(crypto.hasher, sharee.network).join();
        Assert.assertTrue("shared file present via parent", children.size() == 1);

        FileWrapper friend = sharee.getByPath(PathUtil.get(sharer.username)).join().get();
        Set<FileWrapper> friendChildren = friend.getChildren(crypto.hasher, sharee.network).join();
        Assert.assertEquals(friendChildren.size(), 2);
    }

    public static void sharedwithPermutations(NetworkAccess sharerNode, Random rnd) throws Exception {
        String sharerUsername = randomUsername("sharer-", rnd);
        String password = "terriblepassword";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, password, sharerNode, crypto);

        String shareeUsername = randomUsername("sharee-", rnd);
        UserContext sharee = PeergosNetworkUtils.ensureSignedUp(shareeUsername, password, sharerNode, crypto);

        String shareeUsername2 = randomUsername("sharee2-", rnd);
        UserContext sharee2 = PeergosNetworkUtils.ensureSignedUp(shareeUsername2, password, sharerNode, crypto);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), Arrays.asList(sharee));
        friendBetweenGroups(Arrays.asList(sharer), Arrays.asList(sharee2));

        // friends are now connected
        // share a file from u1 to the others
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "afolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        Path p = PathUtil.get(sharerUsername, folderName);

        FileSharedWithState result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 0 && result.writeAccess.size() == 0);

        sharer.shareReadAccessWith(p, Collections.singleton(sharee.username)).join();
        result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 1);

        sharer.shareWriteAccessWith(p, Collections.singleton(sharee2.username)).join();

        result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 1 && result.writeAccess.size() == 1);

        sharer.unShareReadAccess(p, sharee.username).join();
        result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 0 && result.writeAccess.size() == 1);

        sharer.unShareWriteAccess(p, sharee2.username).join();
        result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 0 && result.writeAccess.size() == 0);

        // now try again, but after adding read, write sharees, remove the write sharee
        sharer.shareReadAccessWith(p, Collections.singleton(sharee.username)).join();
        result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 1);

        sharer.shareWriteAccessWith(p, Collections.singleton(sharee2.username)).join();

        result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 1 && result.writeAccess.size() == 1);

        sharer.unShareWriteAccess(p, sharee2.username).join();
        result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 1 && result.writeAccess.size() == 0);

    }


    public static void sharedWriteableAndTruncate(NetworkAccess sharerNode, Random rnd) throws Exception {

        String sharerUsername = randomUsername("sharer", rnd);
        String sharerPassword = "sharer1";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerPassword, sharerNode, crypto);

        String shareeUsername = randomUsername("sharee", rnd);
        String shareePassword = "sharee1";
        UserContext sharee = PeergosNetworkUtils.ensureSignedUp(shareeUsername, shareePassword, sharerNode, crypto);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), Arrays.asList(sharee));

        // friends are now connected
        // share a file from u1 to the others
        FileWrapper u1Root = sharer.getUserRoot().join();
        String dirName = "afolder";
        u1Root.mkdir(dirName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();

        Path dirPath = PathUtil.get(sharerUsername, dirName);
        FileWrapper dir = sharer.getByPath(dirPath).join().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = sharer.crypto.random.randomBytes(409);
        AsyncReader resetableFileInputStream = AsyncReader.build(originalFileContents);
        FileWrapper uploaded = dir.uploadOrReplaceFile(filename, resetableFileInputStream, originalFileContents.length,
                sharer.network, crypto, l -> {}).join();

        Path filePath = PathUtil.get(sharerUsername, dirName, filename);
        FileWrapper file = sharer.getByPath(filePath).join().get();
        long originalfileSize = file.getFileProperties().size;
        System.out.println("filesize=" + originalfileSize);

        sharer.shareWriteAccessWith(filePath, Collections.singleton(sharee.username)).join();

        dir = sharer.getByPath(dirPath).join().get();
        byte[] updatedFileContents = sharer.crypto.random.randomBytes(255);
        resetableFileInputStream = AsyncReader.build(updatedFileContents);

        uploaded = dir.uploadOrReplaceFile(filename, resetableFileInputStream, updatedFileContents.length,
                sharer.network, crypto, l -> {}).join();
        file = sharer.getByPath(filePath).join().get();
        long newFileSize = file.getFileProperties().size;
        System.out.println("filesize=" + newFileSize);
        Assert.assertTrue(newFileSize == 255);

        //sharee now attempts to modify file
        FileWrapper sharedFile = sharee.getByPath(filePath).join().get();
        byte[] modifiedFileContents = sharer.crypto.random.randomBytes(255);
        sharedFile.overwriteFileJS(AsyncReader.build(modifiedFileContents), 0, modifiedFileContents.length,
                sharee.network, sharee.crypto, len -> {}).join();
        FileWrapper sharedFileUpdated = sharee.getByPath(filePath).join().get();
        checkFileContents(modifiedFileContents, sharedFileUpdated, sharee);
    }

    public static void renameSharedwithFolder(NetworkAccess sharerNode, Random rnd) throws Exception {
        String sharerUsername = randomUsername("sharer-", rnd);
        String password = "terriblepassword";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, password, sharerNode, crypto);

        String shareeUsername = randomUsername("sharee-", rnd);
        UserContext sharee = PeergosNetworkUtils.ensureSignedUp(shareeUsername, password, sharerNode, crypto);

        friendBetweenGroups(Arrays.asList(sharer), Arrays.asList(sharee));

        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "afolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        Path p = PathUtil.get(sharerUsername, folderName);

        sharer.shareReadAccessWith(p, Set.of(shareeUsername)).join();
        FileSharedWithState result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 1);

        u1Root = sharer.getUserRoot().join();
        FileWrapper file = sharer.getByPath(p).join().get();
        String renamedFolderName= "renamed";
        file.rename(renamedFolderName, u1Root, p, sharer).join();
        p = PathUtil.get(sharerUsername, renamedFolderName);

        sharer.unShareReadAccess(p, sharee.username).join();
        result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 0 && result.writeAccess.size() == 0);

    }

    public static void grantAndRevokeDirReadAccess(NetworkAccess sharerNode, NetworkAccess shareeNode, int shareeCount, Random random) throws Exception {
        Assert.assertTrue(0 < shareeCount);
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String sharerUsername = generateUsername(random);
        String sharerPassword = generatePassword();
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerPassword, sharerNode, crypto);

        List<String> shareePasswords = IntStream.range(0, shareeCount)
                .mapToObj(i -> generatePassword())
                .collect(Collectors.toList());
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount, shareePasswords);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        // share a file from u1 to the others
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "afolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        String path = PathUtil.get(sharerUsername, folderName).toString();
        System.out.println("PATH "+ path);
        FileWrapper folder = sharer.getByPath(path).join().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(originalFileContents);
        FileWrapper updatedFolder = folder.uploadOrReplaceFile(filename, resetableFileInputStream,
                originalFileContents.length, sharer.network, crypto, l -> {}).join();
        String originalFilePath = sharer.username + "/" + folderName + "/" + filename;

        for (int i=0; i< 20; i++) {
            sharer.getByPath(path).join().get()
                    .mkdir("subdir"+i, sharer.network, false, sharer.mirrorBatId(), crypto).join();
        }

        Set<String> childNames = sharer.getByPath(path).join().get().getChildren(crypto.hasher, sharer.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        // file is uploaded, do the actual sharing
        sharer.shareReadAccessWith(PathUtil.get(path),
                shareeUsers.stream()
                        .map(c -> c.username)
                        .collect(Collectors.toSet())).join();

        // check each user can see the shared folder and directory
        for (UserContext sharee : shareeUsers) {
            // test retrieval via getChildren() which is used by the web-ui
            Set<FileWrapper> children = sharee.getByPath(sharer.username).join().get()
                    .getChildren(crypto.hasher, sharee.network).join();
            Assert.assertTrue(children.stream()
                    .filter(f -> f.getName().equals(folderName))
                    .findAny()
                    .isPresent());

            FileWrapper sharedFolder = sharee.getByPath(sharer.username + "/" + folderName).join().orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
            Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

            FileWrapper sharedFile = sharee.getByPath(sharer.username + "/" + folderName + "/" + filename).join().get();
            checkFileContents(originalFileContents, sharedFile, sharee);
        }

        UserContext updatedSharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        List<UserContext> updatedSharees = shareeUsers.stream()
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, shareePasswords.get(shareeUsers.indexOf(e)), e.network.clear(), crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);
                    }
                }).collect(Collectors.toList());


        for (int i = 0; i < updatedSharees.size(); i++) {
            UserContext user = updatedSharees.get(i);
            updatedSharer.unShareReadAccess(PathUtil.get(updatedSharer.username, folderName), user.username).join();
            Thread.sleep(7_000); // make sure old pointers aren't cached

            Optional<FileWrapper> updatedSharedFolder = user.getByPath(updatedSharer.username + "/" + folderName).join();

            // test that u1 can still access the original file, and user cannot
            Optional<FileWrapper> fileWithNewBaseKey = updatedSharer.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).join();
            Assert.assertTrue(!updatedSharedFolder.isPresent());
            Assert.assertTrue(fileWithNewBaseKey.isPresent());

            // Now modify the file
            byte[] suffix = "Some new data at the end".getBytes();
            AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
            FileWrapper parent = updatedSharer.getByPath(updatedSharer.username + "/" + folderName).join().get();
            parent.uploadFileSection(filename, suffixStream, false, originalFileContents.length,
                    originalFileContents.length + suffix.length, Optional.empty(), true,
                    updatedSharer.network, crypto, l -> {},
                    null, Optional.empty(), null, parent.mirrorBatId()).join();
            FileWrapper extendedFile = updatedSharer.getByPath(originalFilePath).join().get();
            AsyncReader extendedContents = extendedFile.getInputStream(updatedSharer.network, crypto, l -> {}).join();
            byte[] newFileContents = Serialize.readFully(extendedContents, extendedFile.getSize()).join();

            Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));

            Thread.sleep(10_000); // let all pointer caches invalidate
            // test remaining users can still see shared file and folder
            for (int j = i + 1; j < updatedSharees.size(); j++) {
                UserContext otherUser = updatedSharees.get(j);

                Optional<FileWrapper> sharedFolder = otherUser.getByPath(updatedSharer.username + "/" + folderName).join();
                Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getName().equals(folderName));

                FileWrapper sharedFile = otherUser.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).join().get();
                checkFileContents(newFileContents, sharedFile, otherUser);
                Set<String> sharedChildNames = sharedFolder.get().getChildren(crypto.hasher, otherUser.network).join()
                        .stream()
                        .map(f -> f.getName())
                        .collect(Collectors.toSet());
                Assert.assertTrue("Correct children", sharedChildNames.equals(childNames));
            }
        }
    }

    public static void grantAndRevokeDirWriteAccess(NetworkAccess sharerNode,
                                                    NetworkAccess shareeNode,
                                                    int shareeCount,
                                                    Random random) throws Exception {
        Assert.assertTrue(0 < shareeCount);
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String sharerUsername = generateUsername(random);
        String sharerPassword = generatePassword();
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerPassword, sharerNode, crypto);

        List<String> shareePasswords = IntStream.range(0, shareeCount)
                .mapToObj(i -> generatePassword())
                .collect(Collectors.toList());
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount, shareePasswords);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        // share a file from u1 to the others
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "afolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        String path = PathUtil.get(sharerUsername, folderName).toString();
        System.out.println("PATH "+ path);
        FileWrapper folder = sharer.getByPath(path).join().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(originalFileContents);
        folder.uploadOrReplaceFile(filename, resetableFileInputStream,
                originalFileContents.length, sharer.network, crypto, l -> {}).join();
        String originalFilePath = sharer.username + "/" + folderName + "/" + filename;

        for (int i=0; i< 20; i++) {
            sharer.getByPath(path).join().get()
                    .mkdir("subdir"+i, sharer.network, false, sharer.mirrorBatId(), crypto).join();
        }

        // file is uploaded, do the actual sharing
        sharer.shareWriteAccessWith(PathUtil.get(path), shareeUsers.stream()
                .map(c -> c.username)
                .collect(Collectors.toSet())).join();

        // upload a image
        String imagename = "small.png";
        byte[] data = Files.readAllBytes(PathUtil.get("assets", "logo.png"));
        FileWrapper sharedFolderv0 = sharer.getByPath(path).join().get();
        sharedFolderv0.uploadOrReplaceFile(imagename, AsyncReader.build(data), data.length,
                sharer.network, crypto, x -> {}).join();

        // create a directory
        FileWrapper sharedFolderv1 = sharer.getByPath(path).join().get();
        sharedFolderv1.mkdir("asubdir", sharer.network, false, sharer.mirrorBatId(), crypto).join();

        UserContext shareeUploader = shareeUsers.get(0);
        // check sharee can see folder via getChildren() which is used by the web-ui
        Set<FileWrapper> children = shareeUploader.getByPath(sharer.username).join().get()
                .getChildren(crypto.hasher, shareeUploader.network).join();
        Assert.assertTrue(children.stream()
                .filter(f -> f.getName().equals(folderName))
                .findAny()
                .isPresent());

        // check a sharee can upload a file
        FileWrapper sharedDir = shareeUploader.getByPath(path).join().get();
        String shareeFilename = "a-new-file.png";
        sharedDir.uploadFileJS(shareeFilename, AsyncReader.build(data), 0, data.length,
                false, shareeUploader.mirrorBatId(), shareeUploader.network, crypto, x -> {}, shareeUploader.getTransactionService(), f -> Futures.of(false)).join();
        FileWrapper newFile = shareeUploader.getByPath(path + "/" + shareeFilename).join().get();
        Assert.assertTrue(newFile.mirrorBatId().equals(sharer.mirrorBatId()));

        Set<String> childNames = sharer.getByPath(path).join().get().getChildren(crypto.hasher, sharer.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        // check each user can see the shared folder and directory
        for (UserContext sharee : shareeUsers) {
            FileWrapper sharedFolder = sharee.getByPath(sharer.username + "/" + folderName).join().orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
            Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

            FileWrapper sharedFile = sharee.getByPath(sharer.username + "/" + folderName + "/" + filename).join().get();
            checkFileContents(originalFileContents, sharedFile, sharee);
            Set<String> sharedChildNames = sharedFolder.getChildren(crypto.hasher, sharee.network).join()
                    .stream()
                    .map(f -> f.getName())
                    .collect(Collectors.toSet());
            Assert.assertTrue("Correct children", sharedChildNames.equals(childNames));
        }

        MultiUserTests.checkUserValidity(sharerNode, sharerUsername);

        UserContext updatedSharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        List<UserContext> updatedSharees = shareeUsers.stream()
                .map(e -> ensureSignedUp(e.username, shareePasswords.get(shareeUsers.indexOf(e)), shareeNode.clear(), crypto))
                .collect(Collectors.toList());


        for (int i = 0; i < updatedSharees.size(); i++) {
            UserContext user = updatedSharees.get(i);
            updatedSharer.unShareWriteAccess(PathUtil.get(updatedSharer.username, folderName), user.username).join();

            Optional<FileWrapper> updatedSharedFolder = user.getByPath(updatedSharer.username + "/" + folderName).join();

            // test that u1 can still access the original file, and user cannot
            Optional<FileWrapper> fileWithNewBaseKey = updatedSharer.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).join();
            Assert.assertTrue(!updatedSharedFolder.isPresent());
            Assert.assertTrue(fileWithNewBaseKey.isPresent());

            // Now modify the file
            byte[] suffix = "Some new data at the end".getBytes();
            AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
            FileWrapper parent = updatedSharer.getByPath(updatedSharer.username + "/" + folderName).join().get();
            parent.uploadFileSection(filename, suffixStream, false, originalFileContents.length,
                    originalFileContents.length + suffix.length, Optional.empty(), true,
                    updatedSharer.network, crypto, l -> {},
                    null, Optional.empty(), null, parent.mirrorBatId()).join();
            FileWrapper extendedFile = updatedSharer.getByPath(originalFilePath).join().get();
            AsyncReader extendedContents = extendedFile.getInputStream(updatedSharer.network, updatedSharer.crypto, l -> {
            }).join();
            byte[] newFileContents = Serialize.readFully(extendedContents, extendedFile.getSize()).join();

            Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));

            // test remaining users can still see shared file and folder
            for (int j = i + 1; j < updatedSharees.size(); j++) {
                UserContext otherUser = updatedSharees.get(j);

                Optional<FileWrapper> sharedFolder = otherUser.getByPath(updatedSharer.username + "/" + folderName).join();
                Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getName().equals(folderName));

                FileWrapper sharedFile = otherUser.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).join().get();
                checkFileContents(newFileContents, sharedFile, otherUser);
                Set<String> sharedChildNames = sharedFolder.get().getChildren(crypto.hasher, otherUser.network).join()
                        .stream()
                        .map(f -> f.getName())
                        .collect(Collectors.toSet());
                Assert.assertTrue("Correct children", sharedChildNames.equals(childNames));
            }
        }
        MultiUserTests.checkUserValidity(sharerNode, sharerUsername);
    }

    public static void grantAndRevokeNestedDirWriteAccess(NetworkAccess network,
                                                          Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 2, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);
        UserContext b = shareeUsers.get(1);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        // share a directory from u1 to u2
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "folder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        Path dirPath = PathUtil.get(sharer.username, folderName);

        FileWrapper folder = sharer.getByPath(dirPath).join().get();
        String filename = "somefile.txt";
        byte[] data = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(data);
        folder.uploadOrReplaceFile(filename, resetableFileInputStream,
                data.length, sharer.network, crypto, l -> {}).join();
        String originalFilePath = sharer.username + "/" + folderName + "/" + filename;

        for (int i=0; i< 20; i++) {
            sharer.getByPath(dirPath).join().get()
                    .mkdir("subdir"+i, sharer.network, false, sharer.mirrorBatId(), crypto).join();
        }

        // share /u1/folder with 'a'
        sharer.shareWriteAccessWith(dirPath, Collections.singleton(a.username)).join();

        // create a directory
        FileWrapper sharedFolderv1 = sharer.getByPath(dirPath).join().get();
        String subdirName = "subdir";
        sharedFolderv1.mkdir(subdirName, sharer.network, false, sharer.mirrorBatId(), crypto).join();

        // share /u1/folder with 'b'
        Path subdirPath = PathUtil.get(sharer.username, folderName, subdirName);
        sharer.shareWriteAccessWith(subdirPath, Collections.singleton(b.username)).join();

        // check 'b' can upload a file
        UserContext shareeUploader = shareeUsers.get(0);
        FileWrapper sharedDir = shareeUploader.getByPath(subdirPath).join().get();
        sharedDir.uploadFileJS("a-new-file.png", AsyncReader.build(data), 0, data.length,
                false, sharedDir.mirrorBatId(), shareeUploader.network, crypto, x -> {}, shareeUploader.getTransactionService(), f -> Futures.of(false)).join();

        Set<String> childNames = sharer.getByPath(dirPath).join().get().getChildren(crypto.hasher, sharer.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        // check 'a' can see the shared directory
        FileWrapper sharedFolder = a.getByPath(sharer.username + "/" + folderName).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

        FileWrapper sharedFile = a.getByPath(sharer.username + "/" + folderName + "/" + filename).join().get();
        checkFileContents(data, sharedFile, a);
        Set<String> sharedChildNames = sharedFolder.getChildren(crypto.hasher, a.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());
        Assert.assertTrue("Correct children", sharedChildNames.equals(childNames));

        MultiUserTests.checkUserValidity(network, sharer.username);

        UserContext updatedSharer = PeergosNetworkUtils.ensureSignedUp(sharer.username, password, network.clear(), crypto);

        List<UserContext> updatedSharees = shareeUsers.stream()
                .map(e -> ensureSignedUp(e.username, password, network.clear(), crypto))
                .collect(Collectors.toList());

        // unshare subdir from 'b'
        UserContext user = updatedSharees.get(1);
        updatedSharer.unShareWriteAccess(subdirPath, b.username).join();

        Optional<FileWrapper> updatedSharedFolder = user.getByPath(updatedSharer.username + "/" + folderName).join();

        // test that u1 can still access the original file, and user cannot
        Optional<FileWrapper> fileWithNewBaseKey = updatedSharer.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).join();
        Assert.assertTrue(!updatedSharedFolder.isPresent());
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file
        byte[] suffix = "Some new data at the end".getBytes();
        AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
        FileWrapper parent = updatedSharer.getByPath(updatedSharer.username + "/" + folderName).join().get();
        parent.uploadFileSection(filename, suffixStream, false, data.length,
                data.length + suffix.length, Optional.empty(), true,
                updatedSharer.network, crypto, l -> {},
                null, Optional.empty(), null, parent.mirrorBatId()).join();
        FileWrapper extendedFile = updatedSharer.getByPath(originalFilePath).join().get();
        AsyncReader extendedContents = extendedFile.getInputStream(updatedSharer.network, updatedSharer.crypto, l -> {}).join();
        byte[] newFileContents = Serialize.readFully(extendedContents, extendedFile.getSize()).join();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(data, suffix)));

        // test 'a' can still see shared file and folder
        UserContext otherUser = updatedSharees.get(0);

        Optional<FileWrapper> folderAgain = otherUser.getByPath(updatedSharer.username + "/" + folderName).join();
        Assert.assertTrue("Shared folder present via direct path", folderAgain.isPresent() && folderAgain.get().getName().equals(folderName));

        FileWrapper sharedFileAgain = otherUser.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).join().get();
        checkFileContents(newFileContents, sharedFileAgain, otherUser);
        Set<String> childNamesAgain = folderAgain.get().getChildren(crypto.hasher, otherUser.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());
        Assert.assertTrue("Correct children", childNamesAgain.equals(childNames));

        MultiUserTests.checkUserValidity(network, sharer.username);
    }

    public static void grantAndRevokeParentNestedWriteAccess(NetworkAccess network,
                                                    Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 2, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);
        UserContext b = shareeUsers.get(1);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        // share a directory from u1 to u2
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "folder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        Path dirPath = PathUtil.get(sharer.username, folderName);

        // create a directory
        String subdirName = "subdir";
        sharer.getByPath(dirPath).join().get()
                .mkdir(subdirName, sharer.network, false, sharer.mirrorBatId(), crypto).join();

        // share /u1/folder/subdir with 'b'
        Path subdirPath = PathUtil.get(sharer.username, folderName, subdirName);
        sharer.shareWriteAccessWith(subdirPath, Collections.singleton(b.username)).join();

        // share /u1/folder with 'a'
        sharer.shareWriteAccessWith(dirPath, Collections.singleton(a.username)).join();

        // check sharer can still see /u1/folder/subdir
        Assert.assertTrue("subdir still present", sharer.getByPath(subdirPath).join().isPresent());

        // check 'a' can see the shared directory
        FileWrapper sharedFolder = a.getByPath(sharer.username + "/" + folderName).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

        // revoke access to /u1/folder from 'a'
        sharer.unShareWriteAccess(dirPath, a.username).join();
        // check 'a' can't see the shared directory
        Optional<FileWrapper> unsharedFolder = a.getByPath(sharer.username + "/" + folderName).join();
        Assert.assertTrue("a can't see unshared folder", ! unsharedFolder.isPresent());
    }

    public static void grantAndRevokeReadAccessToFileInFolder(NetworkAccess network, Random random) throws IOException {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        // share a directory from u1 to u2
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "folder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        Path dirPath = PathUtil.get(sharer.username, folderName);


        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = new byte[1*1024*1024];
        random.nextBytes(originalFileContents);
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);

        FileWrapper dir = sharer.getByPath(dirPath).join().get();

        FileWrapper uploaded = dir.uploadOrReplaceFile(filename, resetableFileInputStream, f.length(),
                sharer.network, crypto, l -> {}).join();


        Path fileToShare = PathUtil.get(sharer.username, folderName, filename);
        sharer.shareReadAccessWith(fileToShare, Collections.singleton(a.username)).join();

        // check 'a' can see the shared file
        FileWrapper sharedFolder = a.getByPath(sharer.username + "/" + folderName + "/" + filename).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, filename);


        sharer.unShareReadAccess(fileToShare, a.username).join();
        // check 'a' can't see the shared directory
        FileWrapper unsharedLocation = a.getByPath(sharer.username).join().get();
        Set<FileWrapper> children = unsharedLocation.getChildren(crypto.hasher, a.network).join();
        Assert.assertTrue("a can't see unshared folder", children.stream().filter(c -> c.getName().equals(folderName)).findFirst().isEmpty());
    }

    public static void grantWriteToFileAndDeleteParent(NetworkAccess network, Random random) throws IOException {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network.clear(), random, 1, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        // share a directory from u1 to u2
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "folder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        Path dirPath = PathUtil.get(sharer.username, folderName);


        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = new byte[1*1024*1024];
        random.nextBytes(originalFileContents);
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);

        FileWrapper dir = sharer.getByPath(dirPath).join().get();

        FileWrapper uploaded = dir.uploadOrReplaceFile(filename, resetableFileInputStream, f.length(),
                sharer.network, crypto, l -> {}).join();


        Path fileToShare = PathUtil.get(sharer.username, folderName, filename);
        sharer.shareWriteAccessWith(fileToShare, Collections.singleton(a.username)).join();

        // check 'a' can see the shared file
        FileWrapper sharedFolder = a.getByPath(sharer.username + "/" + folderName + "/" + filename).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, filename);

        // delete the parent folder
        FileWrapper parent = sharer.getByPath(dirPath).join().get();
        parent.remove(sharer.getUserRoot().join(), dirPath, sharer).join();
        // check 'a' can't see the shared directory
        FileWrapper unsharedLocation = a.getByPath(sharer.username).join().get();
        Set<FileWrapper> children = unsharedLocation.getChildren(crypto.hasher, a.network).join();
        Assert.assertTrue("a can't see unshared folder", children.stream().filter(c -> c.getName().equals(folderName)).findFirst().isEmpty());
    }

    public static void grantAndRevokeWriteThenReadAccessToFolder(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        // share a directory from u1 to u2
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "folder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        Path dirPath = PathUtil.get(sharer.username, folderName);

        // share /u1/folder with 'a'
        sharer.shareWriteAccessWith(dirPath, Collections.singleton(a.username)).join();

        // check 'a' can see the shared file
        FileWrapper sharedFolder = a.getByPath(sharer.username + "/" + folderName).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

        sharer.unShareWriteAccess(dirPath, a.username).join();

        // check 'a' can't see the shared directory
        FileWrapper unsharedLocation = a.getByPath(sharer.username).join().get();
        Set<FileWrapper> children = unsharedLocation.getChildren(crypto.hasher, a.network).join();
        Assert.assertTrue("a can't see unshared folder", children.stream().filter(c -> c.getName().equals(folderName)).findFirst().isEmpty());

        sharer.shareReadAccessWith(dirPath, Collections.singleton(a.username)).join();

        // check 'a' can see the shared file
        sharedFolder = a.getByPath(sharer.username + "/" + folderName).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

        sharer.unShareReadAccess(dirPath, a.username).join();
        // check 'a' can't see the shared directory
        unsharedLocation = a.getByPath(sharer.username).join().get();
        children = unsharedLocation.getChildren(crypto.hasher, a.network).join();
        Assert.assertTrue("a can't see unshared folder", children.stream().filter(c -> c.getName().equals(folderName)).findFirst().isEmpty());
    }

    
    public static void grantAndRevokeDirWriteAccessWithNestedWriteAccess(NetworkAccess network,
                                                                         Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 2, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);
        UserContext b = shareeUsers.get(1);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "folder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        Path dirPath = PathUtil.get(sharer.username, folderName);

        // put a file and some sub-dirs into the dir
        FileWrapper folder = sharer.getByPath(dirPath).join().get();
        String filename = "somefile.txt";
        byte[] data = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(data);
        folder.uploadOrReplaceFile(filename, resetableFileInputStream,
                data.length, sharer.network, crypto, l -> {}).join();

        for (int i=0; i< 20; i++) {
            sharer.getByPath(dirPath).join().get()
                    .mkdir("subdir"+i, sharer.network, false, sharer.mirrorBatId(), crypto).join();
        }

        // grant write access to a directory to user 'a'
        sharer.shareWriteAccessWith(dirPath, Collections.singleton(a.username)).join();

        // create another sub-directory
        FileWrapper sharedFolderv1 = sharer.getByPath(dirPath).join().get();
        String subdirName = "subdir";
        sharedFolderv1.mkdir(subdirName, sharer.network, false, sharer.mirrorBatId(), crypto).join();

        // grant write access to a sub-directory to user 'b'
        Path subdirPath = PathUtil.get(sharer.username, folderName, subdirName);
        sharer.shareWriteAccessWith(subdirPath, Collections.singleton(b.username)).join();

        List<Set<AbsoluteCapability>> childCapsByChunk0 = getAllChildCapsByChunk(sharer.getByPath(dirPath).join().get(), network);
        Assert.assertTrue("Correct links per chunk, without duplicates",
                childCapsByChunk0.stream().map(x -> x.size()).collect(Collectors.toList())
                        .equals(Arrays.asList(10, 10, 2)));

        // check 'b' can upload a file
        UserContext shareeUploader = shareeUsers.get(0);
        FileWrapper sharedDir = shareeUploader.getByPath(subdirPath).join().get();
        sharedDir.uploadFileJS("a-new-file.png", AsyncReader.build(data), 0, data.length,
                false, sharedDir.mirrorBatId(), shareeUploader.network, crypto, x -> {}, shareeUploader.getTransactionService(), f -> Futures.of(false)).join();

        // check 'a' can see the shared directory
        FileWrapper sharedFolder = a.getByPath(sharer.username + "/" + folderName).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

        FileWrapper sharedFile = a.getByPath(sharer.username + "/" + folderName + "/" + filename).join().get();
        checkFileContents(data, sharedFile, a);
        Set<String> sharedChildNames = sharedFolder.getChildren(crypto.hasher, a.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());
        Set<String> childNames = sharer.getByPath(dirPath).join().get().getChildren(crypto.hasher, sharer.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());
        Assert.assertTrue("Correct children", sharedChildNames.equals(childNames));

        MultiUserTests.checkUserValidity(network, sharer.username);

        Set<AbsoluteCapability> childCaps = getAllChildCaps(sharer.getByPath(dirPath).join().get(), network);
        Assert.assertTrue("Correct number of child caps on dir", childCaps.size() == 22);

        UserContext updatedSharer = PeergosNetworkUtils.ensureSignedUp(sharer.username, password, network.clear(), crypto);

        List<UserContext> updatedSharees = shareeUsers.stream()
                .map(e -> ensureSignedUp(e.username, password, network.clear(), crypto))
                .collect(Collectors.toList());

        // revoke write access to top level dir from 'a'
        UserContext user = updatedSharees.get(0);

        List<Set<AbsoluteCapability>> childCapsByChunk1 = getAllChildCapsByChunk(updatedSharer.getByPath(dirPath).join().get(), network);
        Assert.assertTrue("Correct links per chunk, without duplicates",
                childCapsByChunk1.stream().map(x -> x.size()).collect(Collectors.toList())
                        .equals(Arrays.asList(10, 10, 2)));

        updatedSharer.unShareWriteAccess(dirPath, a.username).join();

        List<Set<AbsoluteCapability>> childCapsByChunk2 = getAllChildCapsByChunk(sharer.getByPath(dirPath).join().get(), network);
        Assert.assertTrue("Correct links per chunk, without duplicates",
                childCapsByChunk2.stream().map(x -> x.size()).collect(Collectors.toList())
                        .equals(Arrays.asList(10, 10, 2)));

        Optional<FileWrapper> updatedSharedFolder = user.getByPath(dirPath).join();

        // test that sharer can still access the sub-dir, and 'a' cannot access the top level dir
        Optional<FileWrapper> updatedSubdir = updatedSharer.getByPath(subdirPath).join();
        Assert.assertTrue(! updatedSharedFolder.isPresent());
        Assert.assertTrue(updatedSubdir.isPresent());

        // test 'b' can still see shared sub-dir
        UserContext otherUser = updatedSharees.get(1);

        Optional<FileWrapper> subdirAgain = otherUser.getByPath(subdirPath).join();
        Assert.assertTrue("Shared folder present via direct path", subdirAgain.isPresent());

        MultiUserTests.checkUserValidity(network, sharer.username);
    }

    public static void socialFeed(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        // share a file from u1 to u2
        byte[] fileData = new byte[1*1024*1024];
        random.nextBytes(fileData);
        Path file1 = PathUtil.get(sharer.username, "first-file.txt");
        uploadAndShare(fileData, file1, sharer, a.username);

        // check 'a' can see the shared file in their social feed
        SocialFeed feed = a.getSocialFeed().join();
        int feedSize = 2;
        List<SharedItem> items = feed.getShared(feedSize, feedSize + 1, a.crypto, a.network).join();
        Assert.assertTrue(items.size() > 0);
        SharedItem item = items.get(0);
        Assert.assertTrue(item.owner.equals(sharer.username));
        Assert.assertTrue(item.sharer.equals(sharer.username));
        AbsoluteCapability readCap = sharer.getByPath(file1).join().get().getPointer().capability.readOnly();
        Assert.assertTrue(item.cap.equals(readCap));
        Assert.assertTrue(PathUtil.get(item.path).equals(file1));

        // Test the feed after a fresh login
        UserContext freshA = PeergosNetworkUtils.ensureSignedUp(a.username, password, network, crypto);
        SocialFeed freshFeed = freshA.getSocialFeed().join();
        List<SharedItem> freshItems = freshFeed.getShared(feedSize, feedSize + 1, a.crypto, a.network).join();
        Assert.assertTrue(freshItems.size() > 0);
        SharedItem freshItem = freshItems.get(0);
        Assert.assertTrue(freshItem.equals(item));

        // Test sharing a new item after construction
        Path file2 = PathUtil.get(sharer.username, "second-file.txt");
        uploadAndShare(fileData, file2, sharer, a.username);

        SocialFeed updatedFeed = freshFeed.update().join();
        List<SharedItem> items2 = updatedFeed.getShared(feedSize + 1, feedSize + 2, a.crypto, a.network).join();
        Assert.assertTrue(items2.size() > 0);
        SharedItem item2 = items2.get(0);
        Assert.assertTrue(item2.owner.equals(sharer.username));
        Assert.assertTrue(item2.sharer.equals(sharer.username));
        AbsoluteCapability readCap2 = sharer.getByPath(file2).join().get().getPointer().capability.readOnly();
        Assert.assertTrue(item2.cap.equals(readCap2));

        // check accessing the files normally
        UserContext fresherA = PeergosNetworkUtils.ensureSignedUp(a.username, password, network, crypto);
        Optional<FileWrapper> directFile1 = fresherA.getByPath(file1).join();
        Assert.assertTrue(directFile1.isPresent());
        Optional<FileWrapper> directFile2 = fresherA.getByPath(file2).join();
        Assert.assertTrue(directFile2.isPresent());

        // check feed after browsing to the senders home
        Path file3 = PathUtil.get(sharer.username, "third-file.txt");
        uploadAndShare(fileData, file3, sharer, a.username);

        // browse to sender home
        freshA.getByPath(PathUtil.get(sharer.username)).join();

        Path file4 = PathUtil.get(sharer.username, "fourth-file.txt");
        uploadAndShare(fileData, file4, sharer, a.username);

        // now check feed
        SocialFeed updatedFeed3 = freshFeed.update().join();
        List<SharedItem> items3 = updatedFeed3.getShared(feedSize + 2, feedSize + 4, a.crypto, a.network).join();
        Assert.assertTrue(items3.size() > 0);
        SharedItem item3 = items3.get(0);
        Assert.assertTrue(item3.owner.equals(sharer.username));
        Assert.assertTrue(item3.sharer.equals(sharer.username));
        AbsoluteCapability readCap3 = sharer.getByPath(file3).join().get().getPointer().capability.readOnly();
        Assert.assertTrue(item3.cap.equals(readCap3));

        // social post
        List<Text> postBody = Arrays.asList(new Text("G'day, skip!"));
        SocialPost post = new SocialPost(sharer.username, postBody, LocalDateTime.now(),
                SocialPost.Resharing.Friends, Optional.empty(), Collections.emptyList(), Collections.emptyList());
        Pair<Path, FileWrapper> p = sharer.getSocialFeed().join().createNewPost(post).join();
        sharer.shareReadAccessWith(p.left, Set.of(a.username)).join();
        List<SharedItem> withPost = freshFeed.update().join().getShared(0, feedSize + 5, crypto, fresherA.network).join();
        SharedItem sharedPost = withPost.get(withPost.size() - 1);
        FileWrapper postFile = fresherA.getByPath(sharedPost.path).join().get();
        assertTrue(postFile.getFileProperties().isSocialPost());
        SocialPost receivedPost = Serialize.parse(postFile.getInputStream(network, crypto, x -> {}).join(),
                postFile.getSize(), SocialPost::fromCbor).join();
        assertTrue(receivedPost.body.equals(post.body));
    }

    public static void socialPostPropagation(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext a = PeergosNetworkUtils.ensureSignedUp("a"+generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 2, Arrays.asList(password, password));
        UserContext b = shareeUsers.get(0);
        UserContext c = shareeUsers.get(1);

        // friend a with others, b and c are not friends
        friendBetweenGroups(Arrays.asList(a), shareeUsers);

        // friends are now connected
        // test social post propagation (comment from b on post from a gets to c)
        SocialPost post = new SocialPost(a.username,
                Arrays.asList(new Text("G'day, skip!")), LocalDateTime.now(),
                SocialPost.Resharing.Friends, Optional.empty(),
                Collections.emptyList(), Collections.emptyList());
        SocialFeed feed = a.getSocialFeed().join();
        Pair<Path, FileWrapper> p = feed.createNewPost(post).join();
        String aFriendsUid = a.getGroupUid(SocialState.FRIENDS_GROUP_NAME).join().get();
        a.shareReadAccessWith(p.left, Set.of(aFriendsUid)).join();

        // b receives the post
        SocialFeed bFeed = b.getSocialFeed().join().update().join();
        List<Pair<SharedItem, FileWrapper>> bPosts = bFeed.getSharedFiles(0, 25).join();
        Pair<SharedItem, FileWrapper> sharedPost = bPosts.get(bPosts.size() - 1);

        // b now comments on post from a
        SocialPost reply = new SocialPost(b.username,
                Arrays.asList(new Text("What an entrance!")), LocalDateTime.now(),
                SocialPost.Resharing.Friends,
                Optional.of(new FileRef(sharedPost.left.path, sharedPost.left.cap, post.contentHash(hasher).join())),
                Collections.emptyList(), Collections.emptyList());
        Pair<Path, FileWrapper> replyFromB = bFeed.createNewPost(reply).join();
        String bFriendsUid = b.getGroupUid(SocialState.FRIENDS_GROUP_NAME).join().get();
        b.shareReadAccessWith(replyFromB.left, Set.of(bFriendsUid)).join();

        // make sure a includes a ref to the comment on the original
        a.getSocialFeed().join().update().join();

        // check c gets the post and it references the comment
        List<Pair<SharedItem, FileWrapper>> cPosts = c.getSocialFeed().join().update().join().getSharedFiles(0, 25).join();
        Pair<SharedItem, FileWrapper> cPost = cPosts.get(cPosts.size() - 1);
        SocialPost receivedPost = Serialize.parse(cPost.right.getInputStream(network, crypto, x -> {}).join(),
                cPost.right.getSize(), SocialPost::fromCbor).join();
        Assert.assertTrue(receivedPost.author.equals(a.username));
        Assert.assertTrue(receivedPost.comments.get(0).cap.equals(replyFromB.right.readOnlyPointer()));
    }

    public static void socialFeedBug(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(randomUsername("sharer-", random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext sharee = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        byte[] fileData = new byte[1*1024*1024];
        random.nextBytes(fileData);
        AsyncReader reader = new AsyncReader.ArrayBacked(fileData);

        SocialFeed feed = sharer.getSocialFeed().join();
        FileRef ref = feed.uploadMediaForPost(reader, fileData.length, LocalDateTime.now(), c -> {}).join().right;
        SocialPost.Resharing resharingType = SocialPost.Resharing.Friends;
        List<? extends Content> body = Arrays.asList(new Text("aaaa"), new Reference(ref));
        SocialPost socialPost = SocialPost.createInitialPost(sharer.username, body, resharingType);

        Pair<Path, FileWrapper> result = feed.createNewPost(socialPost).join();

        LocalDateTime postTime = LocalDateTime.now();
        String updatedBody = "bbbbb";
        socialPost = socialPost.edit(Arrays.asList(new Text(updatedBody), new Reference(ref)), postTime);

        String uuid = result.left.getFileName().toString();
        result = feed.updatePost(uuid, socialPost).join();

        String friendGroup = SocialState.FRIENDS_GROUP_NAME;
        SocialState state = sharer.getSocialState().join();
        String groupUid = state.groupNameToUid.get(friendGroup);
        // was Set.of(groupUid)
        //boolean res = sharer.shareReadAccessWith(result.left, Set.of(sharee.username)).join();
        sharer.shareReadAccessWith(result.left, Set.of(groupUid)).join();

        SocialFeed receiverFeed = sharee.getSocialFeed().join().update().join();
        List<Pair<SharedItem, FileWrapper>> files = receiverFeed.getSharedFiles(0, 100).join();
        assertTrue(files.size() == 3);
        FileWrapper socialFile = files.get(files.size() -1).right;
        SharedItem sharedItem = files.get(files.size() -1).left;
        FileProperties props = socialFile.getFileProperties();
        SocialPost loadedSocialPost = Serialize.parse(socialFile, SocialPost::fromCbor, sharee.network, crypto).join();
        assertTrue(loadedSocialPost.body.get(0).inlineText().equals(updatedBody));

        FileRef mediaRef = ((Reference)loadedSocialPost.body.get(1)).ref;
        Optional<FileWrapper> optFile = sharee.network.getFile(mediaRef.cap, sharer.username).join();
        assertTrue(optFile.isPresent());

        //create a reply
        String replyText = "reply";
        Multihash hash = loadedSocialPost.contentHash(sharee.crypto.hasher).join();
        FileRef parent = new FileRef(sharedItem.path, sharedItem.cap, hash);
        SocialPost replySocialPost = SocialPost.createComment(parent, resharingType, sharee.username, Arrays.asList(new Text(replyText)));
        result = receiverFeed.createNewPost(replySocialPost).join();
        String receiverGroupUid = sharee.getSocialState().join().groupNameToUid.get(friendGroup);
        sharee.shareReadAccessWith(result.left, Set.of(receiverGroupUid)).join();

        //now sharer should see the reply
        sharer = UserContext.signIn(sharer.username, password, UserTests::noMfa, false, sharer.network, sharer.crypto, c -> {}).join();
        feed = sharer.getSocialFeed().join().update().join();
        files = feed.getSharedFiles(0, 100).join();
        assertTrue(files.size() == 5);
        socialFile = files.get(files.size() -1).right;
        loadedSocialPost = Serialize.parse(socialFile, SocialPost::fromCbor, sharer.network, crypto).join();
        assertTrue(loadedSocialPost.body.get(0).inlineText().equals(replyText));
    }

    public static void socialFeedAndUnfriending(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(randomUsername("sharer-", random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext sharee = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        SocialFeed feed = sharer.getSocialFeed().join();
        SocialPost.Resharing resharingType = SocialPost.Resharing.Friends;
        String bodyText = "aaaa";
        List<Text> body = Arrays.asList(new Text(bodyText));
        SocialPost socialPost = SocialPost.createInitialPost(sharer.username, body, resharingType);
        Pair<Path, FileWrapper> result = feed.createNewPost(socialPost).join();

        String friendGroup = SocialState.FRIENDS_GROUP_NAME;
        SocialState state = sharer.getSocialState().join();
        String groupUid = state.groupNameToUid.get(friendGroup);
        sharer.shareReadAccessWith(result.left, Set.of(groupUid)).join();

        SocialFeed receiverFeed = sharee.getSocialFeed().join().update().join();
        List<Pair<SharedItem, FileWrapper>> files = receiverFeed.getSharedFiles(0, 100).join();
        assertTrue(files.size() == 3);
        FileWrapper socialFile = files.get(files.size() - 1).right;
        SharedItem sharedItem = files.get(files.size() - 1).left;
        FileProperties props = socialFile.getFileProperties();
        SocialPost loadedSocialPost = Serialize.parse(socialFile, SocialPost::fromCbor, sharee.network, crypto).join();
        assertTrue(loadedSocialPost.body.get(0).inlineText().equals(bodyText));

        //create a reply
        String replyText = "reply";
        Multihash hash = loadedSocialPost.contentHash(sharee.crypto.hasher).join();
        FileRef parent = new FileRef(sharedItem.path, sharedItem.cap, hash);
        SocialPost replySocialPost = SocialPost.createComment(parent, resharingType, sharee.username,
                Arrays.asList(new Text(replyText)));
        result = receiverFeed.createNewPost(replySocialPost).join();
        String receiverGroupUid = sharee.getSocialState().join().groupNameToUid.get(friendGroup);
        sharee.shareReadAccessWith(result.left, Set.of(receiverGroupUid)).join();

        //now sharer should see the reply
        feed = sharer.getSocialFeed().join().update().join();
        files = feed.getSharedFiles(0, 100).join();
        assertTrue(files.size() == 5);
        FileWrapper original = files.get(files.size() - 2).right;
        FileWrapper reply = files.get(files.size() - 1).right;
        SocialPost originalPost = Serialize.parse(original, SocialPost::fromCbor, sharer.network, crypto).join();
        SocialPost replyPost = Serialize.parse(reply, SocialPost::fromCbor, sharer.network, crypto).join();
        assertTrue(originalPost.body.get(0).inlineText().equals(bodyText));
        assertTrue(replyPost.body.get(0).inlineText().equals(replyText));

        sharer.removeFollower(sharee.username).join();
        feed = sharer.getSocialFeed().join().update().join();
        files = feed.getSharedFiles(0, 100).join();
        assertTrue(files.size() == 5);
        FileWrapper post = files.get(files.size() - 2).right;
        SocialPost remainingSocialPost = Serialize.parse(post, SocialPost::fromCbor, sharer.network, crypto).join();
        assertTrue(remainingSocialPost.body.get(0).inlineText().equals(bodyText));

    }

    private static void uploadAndShare(byte[] data, Path file, UserContext sharer, String sharee) {
        String filename = file.getFileName().toString();
        sharer.getByPath(file.getParent()).join().get()
                .uploadOrReplaceFile(filename, AsyncReader.build(data), data.length,
                        sharer.network, crypto, l -> {}).join();
        sharer.shareReadAccessWith(file, Set.of(sharee)).join();
    }

    public static void socialFeedVariations2(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext sharee = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);
        String dir1 = "one";
        String dir2 = "two";
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();
        sharer.getUserRoot().join().mkdir(dir2, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();

        Path dirToShare1 = PathUtil.get(sharer.username, dir1);
        Path dirToShare2 = PathUtil.get(sharer.username, dir2);
        sharer.shareReadAccessWith(dirToShare1, Set.of(sharee.username)).join();
        sharer.shareReadAccessWith(dirToShare2, Set.of(sharee.username)).join();

        SocialFeed feed = sharee.getSocialFeed().join();
        List<SharedItem> items = feed.getShared(0, 1000, sharee.crypto, sharee.network).join();
        Assert.assertTrue(items.size() == 2 + 2);

        sharee.getUserRoot().join().mkdir("mine", sharee.network, false, sharer.mirrorBatId(), sharer.crypto).join();
    }

    public static void socialFeedFailsInUI(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext sharee = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);
        String dir1 = "one";
        String dir2 = "two";
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();
        sharer.getUserRoot().join().mkdir(dir2, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();

        Path dirToShare1 = PathUtil.get(sharer.username, dir1);
        Path dirToShare2 = PathUtil.get(sharer.username, dir2);
        sharer.shareReadAccessWith(dirToShare1, Set.of(sharee.username)).join();
        sharer.shareReadAccessWith(dirToShare2, Set.of(sharee.username)).join();

        SocialFeed feed = sharee.getSocialFeed().join();
        int initialFeedSize = 2;
        List<SharedItem> items = feed.getShared(0, 1000, sharee.crypto, sharee.network).join();
        Assert.assertTrue(items.size() == initialFeedSize + 2);

        sharee = PeergosNetworkUtils.ensureSignedUp(sharee.username, password, network, crypto);
        sharee.getUserRoot().join().mkdir("mine", sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();

        feed = sharee.getSocialFeed().join();
        items = feed.getShared(0, 1000, sharee.crypto, sharee.network).join();
        Assert.assertTrue(items.size() == initialFeedSize + 2);

        //When attempting this in the web-ui the below call results in a failure when loading timeline entry
        //Cannot seek to position 680 in file of length 340
        feed = sharee.getSocialFeed().join();
        items = feed.getShared(0, 1000, sharee.crypto, sharee.network).join();
        Assert.assertTrue(items.size() == initialFeedSize + 2);
    }

    public static void socialFeedEmpty(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        SocialFeed feed = sharer.getSocialFeed().join();
        List<SharedItem> items = feed.getShared(0, 1, sharer.crypto, sharer.network).join();
        Assert.assertTrue(items.size() == 0);
    }

    public static void socialFeedVariations(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext a = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);
        String dir1 = "one";
        String dir2 = "two";
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();
        sharer.getUserRoot().join().mkdir(dir2, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();

        Path dirToShare1 = PathUtil.get(sharer.username, dir1);
        Path dirToShare2 = PathUtil.get(sharer.username, dir2);
        sharer.shareReadAccessWith(dirToShare1, Set.of(a.username)).join();
        sharer.shareReadAccessWith(dirToShare2, Set.of(a.username)).join();

        SocialFeed feed = a.getSocialFeed().join();
        int initialFeedSize = 2;
        List<SharedItem> items = feed.getShared(0, 1000, a.crypto, a.network).join();
        Assert.assertTrue(items.size() == initialFeedSize + 2);

        //Add another file and share
        String dir3 = "three";
        sharer.getUserRoot().join().mkdir(dir3, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();

        Path dirToShare3 = PathUtil.get(sharer.username, dir3);
        sharer.shareReadAccessWith(dirToShare3, Set.of(a.username)).join();

        feed = a.getSocialFeed().join().update().join();
        items = feed.getShared(0, 1000, a.crypto, a.network).join();
        Assert.assertTrue(items.size() == initialFeedSize + 3);
    }

    public static void chatReplyWithAttachment(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext a = PeergosNetworkUtils.ensureSignedUp("a-" + generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password));
        UserContext b = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(a), shareeUsers);

        Messenger msgA = new Messenger(a);
        ChatController controllerA = msgA.createChat().join();
        controllerA = msgA.invite(controllerA, Arrays.asList(b.username), Arrays.asList(b.signer.publicKeyHash)).join();
        List<Pair<SharedItem, FileWrapper>> feed = b.getSocialFeed().join().update().join().getSharedFiles(0, 10).join();

        ApplicationMessage msg1 = ApplicationMessage.text("G'day mate!");
        controllerA = msgA.sendMessage(controllerA, msg1).join();
        List<MessageEnvelope> initialMessages = controllerA.getMessages(0, 10).join();
        MessageEnvelope lastMessage = initialMessages.get(initialMessages.size() - 1);

        byte[] media = "Some media data".getBytes();
        AsyncReader reader = AsyncReader.build(media);
        Pair<String, FileRef> mediaRef = msgA.uploadMedia(controllerA, reader, "txt", media.length,
                LocalDateTime.now(), x -> {
                }).join();
        ReplyTo msg2 = ReplyTo.build(lastMessage, ApplicationMessage.attachment("Isn't this cool!!",
                Arrays.asList(mediaRef.right)), hasher).join();
        controllerA = msgA.sendMessage(controllerA, msg2).join();
    }

    public static void email(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";
        UserContext user = PeergosNetworkUtils.ensureSignedUp("a-" + generateUsername(random), password, network, crypto);
        UserContext email = PeergosNetworkUtils.ensureSignedUp("email-"+ generateUsername(random), password, network, crypto);

        App emailApp = App.init(user, "email").join();
        EmailClient client = EmailClient.load(emailApp, crypto).join();
        client = EmailClient.load(emailApp, crypto).join(); //test that keys are found and loaded
        client.connectToBridge(user, email.username).join();

        Optional<String> emailAddress =  client.getEmailAddress().join();
        Assert.assertTrue("email address", emailAddress.isEmpty());

        //email bridge setup
        email.sendReplyFollowRequest(email.processFollowRequests().join().get(0), true, true).join();
        user.processFollowRequests().join();
        EmailBridgeClient bridge = EmailBridgeClient.build(email, user.username, user.username + "@example.com");

        emailAddress =  client.getEmailAddress().join();
        Assert.assertTrue("email address", emailAddress.isPresent());

        // send email to bridge
        String attachmentFilename = "text";
        String attachmentContent = "this is an attachment!";
        byte[] data = attachmentContent.getBytes();
        Map<String, byte[]> attachmentsMap = new HashMap<>();
        String uuid = client.uploadAttachment(data).join();
        attachmentsMap.put(uuid, data);
        List<Attachment> outGoingAttachments = Arrays.asList(new Attachment(attachmentFilename, data.length, "text/plain", uuid));
        EmailMessage msg = new EmailMessage("id", "msgid", user.username, "subject",
            LocalDateTime.now(), Arrays.asList("a@example.com"), Collections.emptyList(), Collections.emptyList(),
            "content", true, true, outGoingAttachments, null,
            Optional.empty(), Optional.empty(), Optional.empty());
        boolean sentEmail = client.send(msg).join();
        Assert.assertTrue("email sent", sentEmail);

        // Receive sent email in bridge
        List<String> filenames = bridge.listOutbox();
        Assert.assertTrue("bridge received email", ! filenames.isEmpty());
        Pair<FileWrapper, EmailMessage> pendingEmail = bridge.getPendingEmail(filenames.get(0));
        Assert.assertTrue(Arrays.equals(msg.serialize(), pendingEmail.right.serialize()));

        Map<String, byte[]> receivedAttachmentsMap = new HashMap<>();
        Attachment attachment = pendingEmail.right.attachments.get(0);
        receivedAttachmentsMap.put(attachment.uuid, bridge.getOutgoingAttachment(attachment.uuid));
        bridge.encryptAndMoveEmailToSent(pendingEmail.left, pendingEmail.right, receivedAttachmentsMap);

        // detect that email's been sent and move to private folder
        List<EmailMessage> sent = client.getNewSent().join();
        Assert.assertTrue(! sent.isEmpty());
        EmailMessage sentEmail2 = sent.get(0);
        client.moveToPrivateSent(sentEmail2).join();
        byte[] attachmentRetrieved =  client.getAttachment(sentEmail2.attachments.get(0).uuid).join();
        String retrievedContent = new String(attachmentRetrieved);
        Assert.assertTrue(retrievedContent.equals(attachmentContent));
        Assert.assertTrue(client.getNewSent().join().isEmpty());

        // receive an inbound email in bridge
        String content2 = "Inbound attachment text";
        byte[] content2Bytes = content2.getBytes();
        Attachment attachment2 = bridge.uploadAttachment("inbound.txt", content2Bytes.length, "text/plain", content2Bytes);

        List<Attachment> inboundAttachments = Arrays.asList(attachment2);
        EmailMessage inMsg = new EmailMessage("id2", "msgid", "alice@crypto.net", "what's up?",
                LocalDateTime.now(), Arrays.asList("ouremail@example.com"), Collections.emptyList(), Collections.emptyList(),
                "content", true, true, inboundAttachments, null,
                Optional.empty(), Optional.empty(), Optional.empty());
        bridge.addToInbox(inMsg);

        // retrieve new message in client
        List<EmailMessage> incoming = client.getNewIncoming().join();
        Assert.assertTrue("received email", ! incoming.isEmpty());
        Assert.assertTrue(Arrays.equals(inMsg.serialize(), incoming.get(0).serialize()));

        // decrypt and move incoming email to private folder
        client.moveToPrivateInbox(incoming.get(0)).join();
        byte[] attachmentRetrieved2 =  client.getAttachment(incoming.get(0).attachments.get(0).uuid).join();
        String retrievedContent2 = new String(attachmentRetrieved2);
        Assert.assertTrue(retrievedContent2.equals(content2));
        Assert.assertTrue(client.getNewIncoming().join().isEmpty());
    }

    public static void chatMultipleInvites(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext a = PeergosNetworkUtils.ensureSignedUp("a-" + generateUsername(random), password, network, crypto);
        int otherMembersCount = 5;
        List<String> passwords = IntStream.range(0, otherMembersCount)
                .mapToObj(i -> generatePassword())
                .collect(Collectors.toList());
        List<UserContext> shareeUsers = getUserContextsForNode(network, random, otherMembersCount, passwords);
        UserContext b = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(a), shareeUsers);

        Messenger msgA = new Messenger(a);
        ChatController controllerA = msgA.createChat().join();
        List<String> otherMembersUsernames = shareeUsers.stream().map(u -> u.username).collect(Collectors.toList());
        List<PublicKeyHash> otherMembersPublicKeyHash = shareeUsers.stream().map(u -> u.signer.publicKeyHash).collect(Collectors.toList());

        controllerA = msgA.invite(controllerA, otherMembersUsernames, otherMembersPublicKeyHash).join();
        Set<String> allMemberNames = controllerA.getMemberNames();
        Assert.assertTrue("all members", allMemberNames.size() == otherMembersCount + 1);

        List<MessageEnvelope> messages = controllerA.getMessages(0, 10).join();
        List<MessageEnvelope> inviteMessages = messages.stream().filter(m -> m.payload.type() == Message.Type.Invite).collect(Collectors.toList());
        Assert.assertTrue("all invites", inviteMessages.size() == otherMembersCount);
    }

    public static void chat(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext a = PeergosNetworkUtils.ensureSignedUp("a-" + generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password));
        UserContext b = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(a), shareeUsers);

        Messenger msgA = new Messenger(a);
        ChatController controllerA = msgA.createChat().join();
        Assert.assertTrue("creator is admin", controllerA.getAdmins().equals(Collections.singleton(a.username)));
        controllerA = msgA.invite(controllerA, Arrays.asList(b.username), Arrays.asList(b.signer.publicKeyHash)).join();
        List<Pair<SharedItem, FileWrapper>> feed = b.getSocialFeed().join().update().join().getSharedFiles(0, 10).join();
        FileWrapper chatSharedDir = feed.get(feed.size() - 1).right;

        Messenger msgB = new Messenger(b);
        ChatController controllerB = msgB.cloneLocallyAndJoin(chatSharedDir).join();
        controllerB = msgB.mergeMessages(controllerB, a.username).join();

        List<MessageEnvelope> initialMessages = controllerB.getMessages(0, 10).join();
        Assert.assertEquals(initialMessages.size(), 4);
        Assert.assertEquals(controllerA.host().messagesMergedUpto, 3);
        Assert.assertEquals(controllerB.host().messagesMergedUpto, 4);

        ApplicationMessage msg1 = ApplicationMessage.text("G'day mate!");
        controllerA = msgA.sendMessage(controllerA, msg1).join();
        Assert.assertEquals(controllerA.host().messagesMergedUpto, 4);
        controllerB = msgB.mergeMessages(controllerB, a.username).join();
        List<MessageEnvelope> messages = controllerB.getMessages(0, 10).join();
        Assert.assertEquals(messages.size(), 5);
        MessageEnvelope fromA = messages.get(messages.size() - 1);
        Assert.assertEquals(fromA.payload, msg1);
        Assert.assertEquals(controllerB.host().messagesMergedUpto, 5);

        ReplyTo msg2 = ReplyTo.build(fromA, ApplicationMessage.text("Isn't this cool!!"), hasher).join();
        controllerB = msgB.sendMessage(controllerB, msg2).join();
        controllerA = msgA.mergeMessages(controllerA, b.username).join();
        List<MessageEnvelope> messagesA = controllerA.getMessages(0, 10).join();
        MessageEnvelope fromB = messagesA.get(5);
        Assert.assertEquals(messagesA.size(), 6);
        Assert.assertEquals(messagesA.get(messagesA.size() - 1).payload, msg2);
        Assert.assertEquals(controllerA.host().messagesMergedUpto, 6);
        Assert.assertEquals(controllerB.host().messagesMergedUpto, 6);
        Assert.assertTrue(fromB.payload instanceof ReplyTo);
        MessageRef parentRef = ((ReplyTo) fromB.payload).parent;
        MessageEnvelope parent = controllerA.getMessageFromRef(parentRef, 4).join();
        Assert.assertTrue(parent.equals(fromA));

        // test setting group properties
        String random_chat = "Random chat";
        controllerA = msgA.setGroupProperty(controllerA, "name", random_chat).join();
        controllerB = msgB.mergeMessages(controllerB, a.username).join();
        String groupName = controllerB.getGroupProperty("name");
        Assert.assertTrue(groupName.equals(random_chat));
        Assert.assertEquals(controllerA.host().messagesMergedUpto, 7);
        Assert.assertEquals(controllerB.host().messagesMergedUpto, 7);

        // make message log multi chunk
        for (int i=0; i < 6; i++) {
            ApplicationMessage msgn = ApplicationMessage.text(new String(new byte[1024 * 1024]));
            controllerA = msgA.sendMessage(controllerA, msgn).join();
        }

        List<MessageEnvelope> last = controllerA.getMessages(12, 13).join();
        controllerB = msgB.mergeMessages(controllerB, a.username).join();
        controllerB.getMessages(12, 13).join();

        // share a media file
        byte[] media = "Some media data".getBytes();
        AsyncReader reader = AsyncReader.build(media);
        Pair<String, FileRef> mediaRef = msgA.uploadMedia(controllerA, reader, "txt", media.length, LocalDateTime.now(), x -> {}).join();
        List<Content> content = Arrays.asList(new Reference(mediaRef.right), new Text("Check out this sunset!"));
        controllerA = msgA.sendMessage(controllerA, new ApplicationMessage(content)).join();
        controllerB = msgB.mergeMessages(controllerB, a.username).join();
        List<MessageEnvelope> withMediaMessage = controllerB.getMessages(0, 50).join();
        MessageEnvelope mediaMessage = withMediaMessage.get(withMediaMessage.size() - 1);
        Assert.assertTrue(mediaMessage.payload instanceof ApplicationMessage);
        Optional<FileRef> ref = ((ApplicationMessage) mediaMessage.payload).body.stream().flatMap(c -> c.reference().stream()).findFirst();
        Assert.assertTrue("Message with media ref present", ref.isPresent());
        FileRef fileRef = ref.get();
        Optional<FileWrapper> mediaFile = a.getByPath(fileRef.path).join();
        Assert.assertTrue(mediaFile.isPresent());

        // remove member from chat
        controllerA = msgA.removeMember(controllerA, b.username).join();
        controllerA = msgA.sendMessage(controllerA, new ApplicationMessage(Arrays.asList(new Text("B shouldn't see this!")))).join();
        controllerB = msgB.mergeMessages(controllerB, a.username).join();
        List<MessageEnvelope> all = controllerB.getMessages(0, 50).join();
        Assert.assertEquals(all.size(), withMediaMessage.size());
        Assert.assertTrue(controllerB.getMemberNames().size() == 1);

        // recent messages
        List<MessageEnvelope> recentA = controllerA.getRecent();
        Assert.assertTrue(recentA.size() > 0);

        // removal status
        Member originalB = controllerA.getMember(b.username);
        Id originalBId = originalB.id;
        Assert.assertTrue(originalB.removed);

        // reinvite member
        controllerA = msgA.invite(controllerA, Arrays.asList(b.username), Arrays.asList(b.signer.publicKeyHash)).join();
        Assert.assertTrue(! controllerA.getMember(b.username).removed);

        // rejoin chat
        controllerB = msgB.mergeMessages(controllerB, a.username).join();
        Member newB = controllerB.getMember(b.username);
        boolean bRemoved2 = newB.removed;
        Assert.assertTrue(! bRemoved2);

        Id newBId = newB.id;
        Assert.assertTrue(! originalBId.equals(newBId));
        PublicKeyHash newChatId = newB.chatIdentity.get().getAndVerifyOwner(b.signer.publicKeyHash, network.dhtClient).join();
        PublicKeyHash oldChatId = originalB.chatIdentity.get().getAndVerifyOwner(b.signer.publicKeyHash, network.dhtClient).join();
        Assert.assertTrue("New chat identity", !newChatId.equals(oldChatId));
    }

    public static void concurrentChatMerges(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext a = PeergosNetworkUtils.ensureSignedUp("a-" + generateUsername(random), password, network, crypto);
        UserContext b = PeergosNetworkUtils.ensureSignedUp("b-" + generateUsername(random), password, network, crypto);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(a), Arrays.asList(b));

        Messenger msgA = new Messenger(a);
        ChatController controllerA = msgA.createChat().join();
        controllerA = msgA.invite(controllerA, Arrays.asList(b.username), Arrays.asList(b.signer.publicKeyHash)).join();

        Pair<Messenger, ChatController> bInit = joinChat(b);
        Messenger msgB = bInit.left;
        ChatController controllerB = bInit.right;

        controllerA = msgA.mergeAllUpdates(controllerA, a.getSocialState().join()).join();

        ApplicationMessage msg1 = ApplicationMessage.text("G'day mate!");
        ApplicationMessage msg2 = ApplicationMessage.text("G'day again!");
        controllerA = msgA.sendMessage(controllerA, msg1).join();
        controllerA = msgA.sendMessage(controllerA, msg2).join();

        // B merges ne messages concurrently in two places
        UserContext b2 = PeergosNetworkUtils.ensureSignedUp(b.username, password, network.clear(), crypto);
        Messenger msgB2 = new Messenger(b2);
        ChatController controllerB2 = msgB2.getChat(controllerB.chatUuid).join();
        ForkJoinTask<?> concurrent = ForkJoinPool.commonPool().submit(() -> {
            msgB2.mergeMessages(controllerB2, a.username).join();
        });

        controllerB = msgB.mergeMessages(controllerB, a.username).join();
        concurrent.join();

        UserContext b3 = PeergosNetworkUtils.ensureSignedUp(b.username, password, network.clear(), crypto);
        Messenger msgB3 = new Messenger(b3);
        ChatController controllerB3 = msgB3.getChat(controllerB.chatUuid).join();
        List<MessageEnvelope> messages = controllerB3.getMessages(0, 20).join();
        int msgCount = messages.stream().filter(m -> m.payload.equals(msg1)).collect(Collectors.toList()).size();
        Assert.assertTrue(msgCount <= 1);
    }

    private static Pair<Messenger, ChatController> joinChat(UserContext c) {
        List<Pair<SharedItem, FileWrapper>> feed = c.getSocialFeed().join().update().join().getSharedFiles(0, 10).join();
        FileWrapper chatSharedDir = feed.stream()
                .filter(p -> p.left.path.contains("/.messaging/"))
                .findAny().get().right;
        Messenger msg = new Messenger(c);
        ChatController controller = msg.cloneLocallyAndJoin(chatSharedDir).join();
        return new Pair<>(msg, controller);
    }

    public static void memberLeaveAndDeleteChat(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext a = PeergosNetworkUtils.ensureSignedUp("a-" + generateUsername(random), password, network, crypto);
        UserContext b = PeergosNetworkUtils.ensureSignedUp("b-" + generateUsername(random), password, network, crypto);
        UserContext c = PeergosNetworkUtils.ensureSignedUp("c-" + generateUsername(random), password, network, crypto);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(a, b), Arrays.asList(c));
        friendBetweenGroups(Arrays.asList(a), Arrays.asList(b));

        Messenger msgA = new Messenger(a);
        ChatController controllerA = msgA.createChat().join();
        controllerA = msgA.invite(controllerA, Arrays.asList(b.username), Arrays.asList(b.signer.publicKeyHash)).join();
        controllerA = msgA.invite(controllerA, Arrays.asList(c.username), Arrays.asList(c.signer.publicKeyHash)).join();

        Pair<Messenger, ChatController> bInit = joinChat(b);
        Messenger msgB = bInit.left;
        ChatController controllerB = bInit.right;

        Pair<Messenger, ChatController> cInit = joinChat(c);
        Messenger msgC = cInit.left;
        ChatController controllerC = cInit.right;

        controllerA = msgA.mergeAllUpdates(controllerA, a.getSocialState().join()).join();

        ApplicationMessage msg1 = ApplicationMessage.text("G'day mate!");
        controllerA = msgA.sendMessage(controllerA, msg1).join();

        controllerB = msgB.mergeMessages(controllerB, a.username).join();
        List<MessageEnvelope> messages = controllerB.getMessages(0, 10).join();
        MessageEnvelope fromA = messages.get(messages.size() - 1);
        Assert.assertEquals(fromA.payload, msg1);

        // C deletes their mirror
        msgC.deleteChat(controllerC).join();

        // B sends a message
        ApplicationMessage msg2 = ApplicationMessage.text("You still here, A?");
        controllerB = msgB.sendMessage(controllerB, msg2).join();

        controllerB = msgB.mergeAllUpdates(controllerB, b.getSocialState().join()).join();
        Assert.assertTrue(controllerB.deletedMemberNames().contains(c.username));
        controllerA = msgA.mergeAllUpdates(controllerA, a.getSocialState().join()).join();
        List<MessageEnvelope> recentA = controllerA.getRecent();
        Assert.assertTrue(recentA.stream().anyMatch(m -> m.payload.equals(msg2)));
        Assert.assertEquals(controllerA.getMemberNames(), Stream.of(a.username, b.username).collect(Collectors.toSet()));
    }

    public static void editChatMessage(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";

        UserContext a = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 1, Arrays.asList(password));
        UserContext b = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(a), shareeUsers);

        Messenger msgA = new Messenger(a);
        ChatController controllerA = msgA.createChat().join();
        controllerA = msgA.invite(controllerA, Arrays.asList(b.username), Arrays.asList(b.signer.publicKeyHash)).join();

        ApplicationMessage msg1 = ApplicationMessage.text("G'day mate!");
        controllerA = msgA.sendMessage(controllerA, msg1).join();

        controllerA = msgA.mergeMessages(controllerA, a.username).join();
        List<MessageEnvelope> messages = controllerA.getMessages(0, 10).join();
        Assert.assertEquals(messages.size(), 4);
        MessageEnvelope envelope = messages.get(messages.size()-1);

        MessageRef messageRef = controllerA.generateHash(envelope).join();
        String changedContent = "edited";
        EditMessage editMessage = new EditMessage(messageRef, ApplicationMessage.text(changedContent));
        controllerA = msgA.sendMessage(controllerA, editMessage).join();
        controllerA = msgA.mergeMessages(controllerA, a.username).join();
        messages = controllerA.getMessages(0, 10).join();
        Assert.assertEquals(messages.size(), 5);
        envelope = messages.get(messages.size()-1);
        EditMessage appMsg = (EditMessage) envelope.payload;
        String msgContent = appMsg.content.body.get(0).inlineText();
        Assert.assertEquals(msgContent, changedContent);

        messageRef = controllerA.generateHash(envelope).join();
        DeleteMessage delMessage = new DeleteMessage(messageRef);
        controllerA = msgA.sendMessage(controllerA, delMessage).join();
        controllerA = msgA.mergeMessages(controllerA, a.username).join();
        messages = controllerA.getMessages(0, 10).join();
        Assert.assertEquals(messages.size(), 6);
    }

    public static void groupSharing(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network.clear(), random, 2, Arrays.asList(password, password));
        UserContext friend = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);
        String dirName = "one";
        sharer.getUserRoot().join().mkdir(dirName, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();

        Path dirToShare1 = PathUtil.get(sharer.username, dirName);
        SocialState social = sharer.getSocialState().join();
        String friends = social.getFriendsGroupUid();
        sharer.shareReadAccessWith(dirToShare1, Set.of(friends)).join();

        FileSharedWithState fileSharedWithState = sharer.sharedWith(dirToShare1).join();
        Assert.assertTrue(fileSharedWithState.readAccess.size() == 1);
        Assert.assertTrue(fileSharedWithState.readAccess.contains(friends));

        Optional<FileWrapper> dir = friend.getByPath(dirToShare1).join();
        Assert.assertTrue(dir.isPresent());

        Optional<FileWrapper> home = friend.getByPath(PathUtil.get(sharer.username)).join();
        Assert.assertTrue(home.isPresent());

        Optional<FileWrapper> dirViaGetChild = home.get().getChild(dirName, sharer.crypto.hasher, sharer.network).join();
        Assert.assertTrue(dirViaGetChild.isPresent());

        Set<FileWrapper> children = home.get().getChildren(sharer.crypto.hasher, friend.network).join();
        Assert.assertTrue(children.size() > 1);

        // remove friend, which should rotate all keys of things shared with the friends group
        sharer.removeFollower(friend.username).join();

        Optional<FileWrapper> dir2 = friend.getByPath(dirToShare1).join();
        Assert.assertTrue(dir2.isEmpty());

        // new friends
        List<UserContext> newFriends = getUserContextsForNode(network, random, 1, Arrays.asList(password, password));
        UserContext newFriend = newFriends.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), newFriends);

        Optional<FileWrapper> dirForNewFriend = newFriend.getByPath(dirToShare1).join();
        Assert.assertTrue(dirForNewFriend.isPresent());

        UserContext oldFriend = shareeUsers.get(1);
        Optional<FileWrapper> dirForOldFriend = oldFriend.getByPath(dirToShare1).join();
        Assert.assertTrue(dirForOldFriend.isPresent());
    }

    public static void groupSharingToFollowers(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network, random, 2, Arrays.asList(password, password));
        UserContext friend = shareeUsers.get(0);

        // make others follow sharer
        followBetweenGroups(Arrays.asList(sharer), shareeUsers);
        String dir1 = "one";
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();

        Path dirToShare1 = PathUtil.get(sharer.username, dir1);
        SocialState social = sharer.getSocialState().join();
        String followers = social.getFollowersGroupUid();
        sharer.shareReadAccessWith(dirToShare1, Set.of(followers)).join();

        FileSharedWithState fileSharedWithState = sharer.sharedWith(dirToShare1).join();
        Assert.assertTrue(fileSharedWithState.readAccess.size() == 1);
        Assert.assertTrue(fileSharedWithState.readAccess.contains(followers));

        Optional<FileWrapper> dir = friend.getByPath(dirToShare1).join();
        Assert.assertTrue(dir.isPresent());

        // remove friend, which should rotate all keys of things shared with the friends group
        sharer.removeFollower(friend.username).join();

        Optional<FileWrapper> dir2 = friend.getByPath(dirToShare1).join();
        Assert.assertTrue(dir2.isEmpty());
    }

    public static void groupReadIndividualWrite(NetworkAccess network, Random random) {
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String password = "notagoodone";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(network.clear(), random, 2, Arrays.asList(password, password));
        UserContext friend = shareeUsers.get(0);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);
        String dirName = "one";
        sharer.getUserRoot().join().mkdir(dirName, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();

        Path dirToShare1 = PathUtil.get(sharer.username, dirName);
        SocialState social = sharer.getSocialState().join();
        String friends = social.getFriendsGroupUid();
        sharer.shareReadAccessWith(dirToShare1, Set.of(friends)).join();

        FileSharedWithState fileSharedWithState = sharer.sharedWith(dirToShare1).join();
        Assert.assertTrue(fileSharedWithState.readAccess.size() == 1);
        Assert.assertTrue(fileSharedWithState.readAccess.contains(friends));

        sharer.shareWriteAccessWith(dirToShare1, Set.of(friend.username)).join();

        Optional<FileWrapper> dir = friend.getByPath(dirToShare1).join();
        Assert.assertTrue(dir.isPresent() && dir.get().isWritable());

        Optional<FileWrapper> home = friend.getByPath(PathUtil.get(sharer.username)).join();
        Assert.assertTrue(home.isPresent());

        Optional<FileWrapper> dirViaGetChild = home.get().getChild(dirName, sharer.crypto.hasher, sharer.network).join();
        Assert.assertTrue(dirViaGetChild.isPresent() && dirViaGetChild.get().isWritable());
    }

    public static void groupAwareSharing(NetworkAccess network, Random random,
                                         TriFunction<UserContext, Path, Set<String>, CompletableFuture<Snapshot>> shareFunction,
                                         TriFunction<UserContext, Path, Set<String>, CompletableFuture<Snapshot>> unshareFunction,
                                         TriFunction<UserContext, Path, FileSharedWithState, Integer> resultFunc) {
        CryptreeNode.setMaxChildLinkPerBlob(10);
        String password = "notagoodone";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);
        UserContext shareeFriend = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);
        UserContext shareeFollower = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        followBetweenGroups(Arrays.asList(sharer), Arrays.asList(shareeFollower));
        friendBetweenGroups(Arrays.asList(sharer), Arrays.asList(shareeFriend));

        String dir1 = "one";
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.mirrorBatId(), sharer.crypto).join();
        Path dirToShare1 = PathUtil.get(sharer.username, dir1);
        SocialState social = sharer.getSocialState().join();
        String followers = social.getFollowersGroupUid();
        String friends = social.getFriendsGroupUid();

        shareFunction.apply(sharer, dirToShare1, Set.of(shareeFriend.username)).join();
        shareFunction.apply(sharer, dirToShare1, Set.of(shareeFollower.username)).join();
        shareFunction.apply(sharer, dirToShare1, Set.of(followers)).join();
        shareFunction.apply(sharer, dirToShare1, Set.of(friends)).join();

        FileSharedWithState fileSharedWithState = sharer.sharedWith(dirToShare1).join();
        Assert.assertTrue(resultFunc.apply(sharer, dirToShare1, fileSharedWithState) == 4);

        unshareFunction.apply(sharer, dirToShare1, Set.of(friends, followers)).join();

        fileSharedWithState = sharer.sharedWith(dirToShare1).join();
        Assert.assertTrue(resultFunc.apply(sharer, dirToShare1, fileSharedWithState) == 0);
    }

    public static List<Set<AbsoluteCapability>> getAllChildCapsByChunk(FileWrapper dir, NetworkAccess network) {
        return getAllChildCapsByChunk(dir.getPointer().capability, dir.getPointer().fileAccess, dir.version, network);
    }

    public static List<Set<AbsoluteCapability>> getAllChildCapsByChunk(AbsoluteCapability cap,
                                                                       CryptreeNode dir,
                                                                       Snapshot inVersion,
                                                                       NetworkAccess network) {
        Set<NamedAbsoluteCapability> direct = dir.getDirectChildrenCapabilities(cap, inVersion, network).join();

        Pair<byte[], Optional<Bat>> nextLoc = dir.getNextChunkLocation(cap.rBaseKey, Optional.empty(), cap.getMapKey(), cap.bat, null).join();
        AbsoluteCapability nextChunkCap = cap.withMapKey(nextLoc.left, nextLoc.right);

        PointerUpdate pointer = network.mutable.getPointerTarget(cap.owner, cap.writer,
                network.dhtClient).join();
        Snapshot version = new Snapshot(cap.writer,
                WriterData.getWriterData(cap.owner, (Cid) pointer.updated.get(), pointer.sequence, network.dhtClient).join());

        Optional<CryptreeNode> next = network.getMetadata(version.get(nextChunkCap.writer).props.get(), nextChunkCap).join();
        Set<AbsoluteCapability> directUnnamed = direct.stream().map(n -> n.cap).collect(Collectors.toSet());
        if (! next.isPresent())
            return Arrays.asList(directUnnamed);
        return Stream.concat(Stream.of(directUnnamed), getAllChildCapsByChunk(nextChunkCap, next.get(), inVersion, network).stream())
                .collect(Collectors.toList());
    }

    public static Set<AbsoluteCapability> getAllChildCaps(FileWrapper dir, NetworkAccess network) {
        RetrievedCapability p = dir.getPointer();
        AbsoluteCapability cap = p.capability;
        return getAllChildCaps(cap, p.fileAccess, network);
    }

    public static Set<AbsoluteCapability> getAllChildCaps(AbsoluteCapability cap, CryptreeNode dir, NetworkAccess network) {
        PointerUpdate pointer = network.mutable.getPointerTarget(cap.owner, cap.writer,
                network.dhtClient).join();
        return dir.getAllChildrenCapabilities(new Snapshot(cap.writer,
                    WriterData.getWriterData(cap.owner, (Cid) pointer.updated.get(), pointer.sequence, network.dhtClient).join()), cap, crypto.hasher, network).join()
                    .stream().map(n -> n.cap).collect(Collectors.toSet());
    }

    public static void shareFolderForWriteAccess(NetworkAccess sharerNode, NetworkAccess shareeNode, int shareeCount, Random random) throws Exception {
        Assert.assertTrue(0 < shareeCount);

        String sharerUsername = generateUsername(random);
        String sharerPassword = generatePassword();
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerPassword, sharerNode, crypto);

        List<String> shareePasswords = IntStream.range(0, shareeCount)
                .mapToObj(i -> generatePassword())
                .collect(Collectors.toList());
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount, shareePasswords);

        // friend sharer with others
        friendBetweenGroups(Arrays.asList(sharer), shareeUsers);

        // friends are now connected
        // share a directory from u1 to the others
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "awritefolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), Optional.of(Bat.random(crypto.random)), false, sharer.mirrorBatId(), crypto).join();
        String path = PathUtil.get(sharerUsername, folderName).toString();
        System.out.println("PATH "+ path);

        // file is uploaded, do the actual sharing
        sharer.shareWriteAccessWith(PathUtil.get(path),
                shareeUsers.stream()
                        .map(c -> c.username)
                        .collect(Collectors.toSet())).join();

        // check each user can see the shared folder, and write to it
        for (UserContext sharee : shareeUsers) {
            FileWrapper sharedFolder = sharee.getByPath(sharer.username + "/" + folderName).join().orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
            Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

            sharedFolder.mkdir(sharee.username, shareeNode, false, sharedFolder.mirrorBatId(), crypto).join();
        }

        Set<FileWrapper> children = sharer.getByPath(path).join().get().getChildren(crypto.hasher, sharerNode).get();
        Assert.assertTrue(children.size() == shareeCount);
    }

    public static void publicLinkToFile(Random random, NetworkAccess writerNode, NetworkAccess readerNode) throws Exception {
        String username = generateUsername(random);
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, writerNode, crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        String filename = "mediumfile.bin";
        byte[] data = new byte[128*1024];
        random.nextBytes(data);
        long t1 = System.currentTimeMillis();
        userRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(data), false, 0, data.length, Optional.empty(),
                true, context.network, crypto, l -> {}).join();
        long t2 = System.currentTimeMillis();
        String path = "/" + username + "/" + filename;
        FileWrapper file = context.getByPath(path).join().get();
        String link = file.toLink();
        UserContext linkContext = UserContext.fromSecretLink(link, readerNode, crypto).join();
        String entryPath = linkContext.getEntryPath().join();
        Assert.assertTrue("Correct entry path", entryPath.equals("/" + username));
        Optional<FileWrapper> fileThroughLink = linkContext.getByPath(path).join();
        Assert.assertTrue("File present through link", fileThroughLink.isPresent());
    }

    public static void friendBetweenGroups(List<UserContext> a, List<UserContext> b) {
        for (UserContext userA : a) {
            for (UserContext userB : b) {
                // send initial request
                userA.sendFollowRequest(userB.username, SymmetricKey.random()).join();

                // make sharer reciprocate all the follow requests
                List<FollowRequestWithCipherText> sharerRequests = userB.processFollowRequests().join();
                for (FollowRequestWithCipherText u1Request : sharerRequests) {
                    AbsoluteCapability pointer = u1Request.req.entry.get().pointer;
                    Assert.assertTrue("Read only capabilities are shared", ! pointer.wBaseKey.isPresent());
                    boolean accept = true;
                    boolean reciprocate = true;
                    userB.sendReplyFollowRequest(u1Request, accept, reciprocate).join();
                }

                // complete the friendship connection
                userA.processFollowRequests().join();
            }
        }
    }

    public static void followBetweenGroups(List<UserContext> sharers, List<UserContext> followers) {
        for (UserContext userA : sharers) {
            for (UserContext userB : followers) {
                // send initial request
                userB.sendFollowRequest(userA.username, SymmetricKey.random()).join();

                // make sharer reciprocate all the follow requests
                List<FollowRequestWithCipherText> sharerRequests = userA.processFollowRequests().join();
                for (FollowRequestWithCipherText u1Request : sharerRequests) {
                    AbsoluteCapability pointer = u1Request.req.entry.get().pointer;
                    Assert.assertTrue("Read only capabilities are shared", ! pointer.wBaseKey.isPresent());
                    boolean accept = true;
                    boolean reciprocate = false;
                    userA.sendReplyFollowRequest(u1Request, accept, reciprocate).join();
                }

                // complete the friendship connection
                userB.processFollowRequests().join();
            }
        }
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) {
        boolean isRegistered = network.isUsernameRegistered(username).join();
        if (isRegistered)
            return UserContext.signIn(username, password, UserTests::noMfa, network, crypto).join();
        return UserContext.signUp(username, password, "", network, crypto).join();
    }
}
