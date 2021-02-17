package peergos.server.tests;

import org.junit.Assert;
import peergos.server.*;
import peergos.server.storage.ResetableFileInputStream;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.social.*;
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
        return "username_" + Math.abs(random.nextInt() % 1_000_000_000);
    }

    public static String generatePassword() {
        return ArrayOps.bytesToHex(crypto.random.randomBytes(32));
    }

    public static final Crypto crypto = Main.initCrypto();

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
        FileWrapper u1Root = sharerUser.getUserRoot().get();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = sharerUser.crypto.random.randomBytes(10*1024*1024);
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);
        FileWrapper uploaded = u1Root.uploadOrReplaceFile(filename, resetableFileInputStream, f.length(),
                sharerUser.network, crypto, l -> {}, crypto.random.randomBytes(32)).get();

        // share the file from sharer to each of the sharees
        Set<String> shareeNames = shareeUsers.stream()
                .map(u -> u.username)
                .collect(Collectors.toSet());
        sharerUser.shareReadAccessWith(Paths.get(sharerUser.username, filename), shareeNames).join();

        // check other users can read the file
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(sharerUser.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());
            Assert.assertTrue("File is read only", ! sharedFile.get().isWritable());
            checkFileContents(originalFileContents, sharedFile.get(), userContext);
        }

        // check other users can browser to the friend's root
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> friendRoot = userContext.getByPath(sharerUser.username).get();
            assertTrue("friend root present", friendRoot.isPresent());
            Set<FileWrapper> children = friendRoot.get().getChildren(crypto.hasher, userContext.network).get();
            Optional<FileWrapper> sharedFile = children.stream()
                    .filter(file -> file.getName().equals(filename))
                    .findAny();
            assertTrue("Shared file present via root.getChildren()", sharedFile.isPresent());
        }

        UserContext userToUnshareWith = shareeUsers.stream().findFirst().get();

        // unshare with a single user
        sharerUser.unShareReadAccess(Paths.get(sharerUser.username, filename), userToUnshareWith.username).get();

        List<UserContext> updatedShareeUsers = shareeUsers.stream()
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, shareePasswords.get(shareeUsers.indexOf(e)), shareeNode, crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);

                    }
                }).collect(Collectors.toList());

        //test that the other user cannot access it from scratch
        Optional<FileWrapper> otherUserView = updatedShareeUsers.get(0).getByPath(sharerUser.username + "/" + filename).get();
        Assert.assertTrue(!otherUserView.isPresent());

        List<UserContext> remainingUsers = updatedShareeUsers.stream()
                .skip(1)
                .collect(Collectors.toList());

        UserContext updatedSharerUser = ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        // check remaining users can still read it
        for (UserContext userContext : remainingUsers) {
            String path = sharerUser.username + "/" + filename;
            Optional<FileWrapper> sharedFile = userContext.getByPath(path).get();
            Assert.assertTrue("path '" + path + "' is still available", sharedFile.isPresent());
            checkFileContents(originalFileContents, sharedFile.get(), userContext);
        }

        // test that u1 can still access the original file
        Optional<FileWrapper> fileWithNewBaseKey = updatedSharerUser.getByPath(sharerUser.username + "/" + filename).get();
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file
        byte[] suffix = "Some new data at the end".getBytes();
        AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
        FileWrapper parent = updatedSharerUser.getByPath(updatedSharerUser.username).get().get();
        parent.uploadFileSection(filename, suffixStream, false, originalFileContents.length, originalFileContents.length + suffix.length,
                Optional.empty(), true, updatedSharerUser.network, crypto, l -> {},
                null).get();
        AsyncReader extendedContents = updatedSharerUser.getByPath(sharerUser.username + "/" + filename).get().get()
                .getInputStream(updatedSharerUser.network, crypto, l -> {}).get();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).get();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));
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
        FileWrapper u1Root = sharerUser.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = sharerUser.crypto.random.randomBytes(10*1024*1024);
        AsyncReader resetableFileInputStream = AsyncReader.build(originalFileContents);
        FileWrapper uploaded = u1Root.uploadOrReplaceFile(filename, resetableFileInputStream, originalFileContents.length,
                sharerUser.network, crypto, l -> {}, crypto.random.randomBytes(32)).get();

        // share the file from sharer to each of the sharees
        String filePath = sharerUser.username + "/" + filename;
        FileWrapper u1File = sharerUser.getByPath(filePath).get().get();
        byte[] originalStreamSecret = u1File.getFileProperties().streamSecret.get();
        sharerUser.shareWriteAccessWith(Paths.get(sharerUser.username, filename), shareeUsers.stream().map(u -> u.username).collect(Collectors.toSet())).get();

        // check other users can read the file
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(filePath).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());
            Assert.assertTrue("File is writable", sharedFile.get().isWritable());
            checkFileContents(originalFileContents, sharedFile.get(), userContext);
            // check the other user can't rename the file
            FileWrapper parent = userContext.getByPath(sharerUser.username).get().get();
            CompletableFuture<FileWrapper> rename = sharedFile.get()
                    .rename("Somenew name.dat", parent, Paths.get(filePath), userContext);
            assertTrue("Cannot rename", rename.isCompletedExceptionally());
        }

        // check other users can browser to the friend's root
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> friendRoot = userContext.getByPath(sharerUser.username).get();
            assertTrue("friend root present", friendRoot.isPresent());
            Set<FileWrapper> children = friendRoot.get().getChildren(crypto.hasher, userContext.network).get();
            Optional<FileWrapper> sharedFile = children.stream()
                    .filter(file -> file.getName().equals(filename))
                    .findAny();
            assertTrue("Shared file present via root.getChildren()", sharedFile.isPresent());
        }
        MultiUserTests.checkUserValidity(sharerNode, sharerUsername);

        UserContext userToUnshareWith = shareeUsers.stream().findFirst().get();

        // unshare with a single user
        sharerUser.unShareWriteAccess(Paths.get(sharerUser.username, filename), userToUnshareWith.username).join();

        List<UserContext> updatedShareeUsers = shareeUsers.stream()
                .map(e -> ensureSignedUp(e.username, shareePasswords.get(shareeUsers.indexOf(e)), shareeNode, crypto))
                .collect(Collectors.toList());

        //test that the other user cannot access it from scratch
        Optional<FileWrapper> otherUserView = updatedShareeUsers.get(0).getByPath(filePath).get();
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
            Optional<FileWrapper> sharedFile = userContext.getByPath(path).get();
            Assert.assertTrue("path '" + path + "' is still available", sharedFile.isPresent());
            checkFileContents(originalFileContents, sharedFile.get(), userContext);
        }

        // test that u1 can still access the original file
        Optional<FileWrapper> fileWithNewBaseKey = updatedSharerUser.getByPath(filePath).get();
        Assert.assertTrue(fileWithNewBaseKey.isPresent());

        // Now modify the file from the sharer
        byte[] suffix = "Some new data at the end".getBytes();
        AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
        FileWrapper parent = updatedSharerUser.getByPath(updatedSharerUser.username).get().get();
        parent.uploadFileSection(filename, suffixStream, false, originalFileContents.length, originalFileContents.length + suffix.length,
                Optional.empty(), true, updatedSharerUser.network, crypto, l -> {},
                null).get();
        AsyncReader extendedContents = updatedSharerUser.getByPath(filePath).get().get().getInputStream(updatedSharerUser.network,
                updatedSharerUser.crypto, l -> {}).get();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).get();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));

        // Now modify the file from the sharee
        byte[] suffix2 = "Some more data".getBytes();
        AsyncReader suffixStream2 = new AsyncReader.ArrayBacked(suffix2);
        UserContext sharee = remainingUsers.get(0);
        FileWrapper parent2 = sharee.getByPath(updatedSharerUser.username).get().get();
        parent2.uploadFileSection(filename, suffixStream2, false,
                originalFileContents.length + suffix.length,
                originalFileContents.length + suffix.length + suffix2.length,
                Optional.empty(), true, shareeNode, crypto, l -> {},
                null).get();
        AsyncReader extendedContents2 = sharee.getByPath(filePath).get().get()
                .getInputStream(updatedSharerUser.network,
                updatedSharerUser.crypto, l -> {}).get();
        byte[] newFileContents2 = Serialize.readFully(extendedContents2,
                originalFileContents.length + suffix.length + suffix2.length).get();

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
        sharer.getUserRoot().join().mkdir(dirName, sharer.network, false, crypto).join();
        Path dirPath = Paths.get(sharerUsername, dirName);
        sharer.shareWriteAccessWith(dirPath, Collections.singleton(sharee.username)).join();

        // no revoke write access to dir
        sharer.unShareWriteAccess(dirPath, sharee.username).join();

        // check sharee can't read the dir
        Optional<FileWrapper> sharedDir = sharee.getByPath(dirPath).join();
        Assert.assertTrue("unshared dir not present", ! sharedDir.isPresent());

        // upload a file to the dir
        FileWrapper dir = sharer.getByPath(dirPath).join().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = sharer.crypto.random.randomBytes(10*1024*1024);
        AsyncReader resetableFileInputStream = AsyncReader.build(originalFileContents);
        FileWrapper uploaded = dir.uploadOrReplaceFile(filename, resetableFileInputStream, originalFileContents.length,
                sharer.network, crypto, l -> {}, crypto.random.randomBytes(32)).join();

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

        FileWrapper friend = sharee.getByPath(Paths.get(sharer.username)).join().get();
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
        FileWrapper u1Root = sharer.getUserRoot().get();
        String folderName = "afolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).get();
        Path p = Paths.get(sharerUsername, folderName);

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
        FileWrapper u1Root = sharer.getUserRoot().get();
        String dirName = "afolder";
        u1Root.mkdir(dirName, sharer.network, SymmetricKey.random(), false, crypto).get();

        Path dirPath = Paths.get(sharerUsername, dirName);
        FileWrapper dir = sharer.getByPath(dirPath).join().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = sharer.crypto.random.randomBytes(409);
        AsyncReader resetableFileInputStream = AsyncReader.build(originalFileContents);
        FileWrapper uploaded = dir.uploadOrReplaceFile(filename, resetableFileInputStream, originalFileContents.length,
                sharer.network, crypto, l -> {}, crypto.random.randomBytes(32)).join();

        Path filePath = Paths.get(sharerUsername, dirName, filename);
        FileWrapper file = sharer.getByPath(filePath).join().get();
        long originalfileSize = file.getFileProperties().size;
        System.out.println("filesize=" + originalfileSize);

        sharer.shareWriteAccessWith(filePath, Collections.singleton(sharee.username)).join();

        dir = sharer.getByPath(dirPath).join().get();
        byte[] updatedFileContents = sharer.crypto.random.randomBytes(255);
        resetableFileInputStream = AsyncReader.build(updatedFileContents);

        uploaded = dir.uploadOrReplaceFile(filename, resetableFileInputStream, updatedFileContents.length,
                sharer.network, crypto, l -> {}, crypto.random.randomBytes(32)).join();
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

        FileWrapper u1Root = sharer.getUserRoot().get();
        String folderName = "afolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).get();
        Path p = Paths.get(sharerUsername, folderName);

        sharer.shareReadAccessWith(p, Set.of(shareeUsername)).join();
        FileSharedWithState result = sharer.sharedWith(p).join();
        Assert.assertTrue(result.readAccess.size() == 1);

        u1Root = sharer.getUserRoot().get();
        FileWrapper file = sharer.getByPath(p).join().get();
        String renamedFolderName= "renamed";
        file.rename(renamedFolderName, u1Root, p, sharer).join();
        p = Paths.get(sharerUsername, renamedFolderName);

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
        FileWrapper u1Root = sharer.getUserRoot().get();
        String folderName = "afolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).get();
        String path = Paths.get(sharerUsername, folderName).toString();
        System.out.println("PATH "+ path);
        FileWrapper folder = sharer.getByPath(path).get().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(originalFileContents);
        FileWrapper updatedFolder = folder.uploadOrReplaceFile(filename, resetableFileInputStream,
                originalFileContents.length, sharer.network, crypto, l -> {},crypto.random.randomBytes(32)).get();
        String originalFilePath = sharer.username + "/" + folderName + "/" + filename;

        for (int i=0; i< 20; i++) {
            sharer.getByPath(path).join().get()
                    .mkdir("subdir"+i, sharer.network, false, crypto).join();
        }

        Set<String> childNames = sharer.getByPath(path).join().get().getChildren(crypto.hasher, sharer.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        // file is uploaded, do the actual sharing
        boolean finished = sharer.shareReadAccessWith(Paths.get(path),
                shareeUsers.stream()
                        .map(c -> c.username)
                        .collect(Collectors.toSet())).get();

        // check each user can see the shared folder and directory
        for (UserContext sharee : shareeUsers) {
            // test retrieval via getChildren() which is used by the web-ui
            Set<FileWrapper> children = sharee.getByPath(sharer.username).join().get()
                    .getChildren(crypto.hasher, sharee.network).join();
            Assert.assertTrue(children.stream()
                    .filter(f -> f.getName().equals(folderName))
                    .findAny()
                    .isPresent());

            FileWrapper sharedFolder = sharee.getByPath(sharer.username + "/" + folderName).get().orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
            Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

            FileWrapper sharedFile = sharee.getByPath(sharer.username + "/" + folderName + "/" + filename).get().get();
            checkFileContents(originalFileContents, sharedFile, sharee);
        }

        UserContext updatedSharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerPassword, sharerNode.clear(), crypto);

        List<UserContext> updatedSharees = shareeUsers.stream()
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, shareePasswords.get(shareeUsers.indexOf(e)), e.network, crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);
                    }
                }).collect(Collectors.toList());


        for (int i = 0; i < updatedSharees.size(); i++) {
            UserContext user = updatedSharees.get(i);
            updatedSharer.unShareReadAccess(Paths.get(updatedSharer.username, folderName), user.username).get();

            Optional<FileWrapper> updatedSharedFolder = user.getByPath(updatedSharer.username + "/" + folderName).get();

            // test that u1 can still access the original file, and user cannot
            Optional<FileWrapper> fileWithNewBaseKey = updatedSharer.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).get();
            Assert.assertTrue(!updatedSharedFolder.isPresent());
            Assert.assertTrue(fileWithNewBaseKey.isPresent());

            // Now modify the file
            byte[] suffix = "Some new data at the end".getBytes();
            AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
            FileWrapper parent = updatedSharer.getByPath(updatedSharer.username + "/" + folderName).get().get();
            parent.uploadFileSection(filename, suffixStream, false, originalFileContents.length,
                    originalFileContents.length + suffix.length, Optional.empty(), true,
                    updatedSharer.network, crypto, l -> {},
                    null).get();
            FileWrapper extendedFile = updatedSharer.getByPath(originalFilePath).get().get();
            AsyncReader extendedContents = extendedFile.getInputStream(updatedSharer.network, crypto, l -> {}).get();
            byte[] newFileContents = Serialize.readFully(extendedContents, extendedFile.getSize()).get();

            Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));

            // test remaining users can still see shared file and folder
            for (int j = i + 1; j < updatedSharees.size(); j++) {
                UserContext otherUser = updatedSharees.get(j);

                Optional<FileWrapper> sharedFolder = otherUser.getByPath(updatedSharer.username + "/" + folderName).get();
                Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getName().equals(folderName));

                FileWrapper sharedFile = otherUser.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).get().get();
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
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).get();
        String path = Paths.get(sharerUsername, folderName).toString();
        System.out.println("PATH "+ path);
        FileWrapper folder = sharer.getByPath(path).join().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(originalFileContents);
        folder.uploadOrReplaceFile(filename, resetableFileInputStream,
                originalFileContents.length, sharer.network, crypto, l -> {}, crypto.random.randomBytes(32)).join();
        String originalFilePath = sharer.username + "/" + folderName + "/" + filename;

        for (int i=0; i< 20; i++) {
            sharer.getByPath(path).join().get()
                    .mkdir("subdir"+i, sharer.network, false, crypto).join();
        }

        // file is uploaded, do the actual sharing
        boolean finished = sharer.shareWriteAccessWith(Paths.get(path), shareeUsers.stream()
                .map(c -> c.username)
                .collect(Collectors.toSet())).get();

        // upload a image
        String imagename = "small.png";
        byte[] data = Files.readAllBytes(Paths.get("assets", "logo.png"));
        FileWrapper sharedFolderv0 = sharer.getByPath(path).join().get();
        sharedFolderv0.uploadOrReplaceFile(imagename, AsyncReader.build(data), data.length,
                sharer.network, crypto, x -> {}, crypto.random.randomBytes(32)).join();

        // create a directory
        FileWrapper sharedFolderv1 = sharer.getByPath(path).join().get();
        sharedFolderv1.mkdir("asubdir", sharer.network, false, crypto).join();

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
        sharedDir.uploadFileJS("a-new-file.png", AsyncReader.build(data), 0, data.length,
                false, false, shareeUploader.network, crypto, x -> {}, shareeUploader.getTransactionService()).join();

        Set<String> childNames = sharer.getByPath(path).join().get().getChildren(crypto.hasher, sharer.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        // check each user can see the shared folder and directory
        for (UserContext sharee : shareeUsers) {
            FileWrapper sharedFolder = sharee.getByPath(sharer.username + "/" + folderName).get().orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
            Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

            FileWrapper sharedFile = sharee.getByPath(sharer.username + "/" + folderName + "/" + filename).get().get();
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
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, shareePasswords.get(shareeUsers.indexOf(e)), e.network, crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);
                    }
                }).collect(Collectors.toList());


        for (int i = 0; i < updatedSharees.size(); i++) {
            UserContext user = updatedSharees.get(i);
            updatedSharer.unShareWriteAccess(Paths.get(updatedSharer.username, folderName), user.username).get();

            Optional<FileWrapper> updatedSharedFolder = user.getByPath(updatedSharer.username + "/" + folderName).get();

            // test that u1 can still access the original file, and user cannot
            Optional<FileWrapper> fileWithNewBaseKey = updatedSharer.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).get();
            Assert.assertTrue(!updatedSharedFolder.isPresent());
            Assert.assertTrue(fileWithNewBaseKey.isPresent());

            // Now modify the file
            byte[] suffix = "Some new data at the end".getBytes();
            AsyncReader suffixStream = new AsyncReader.ArrayBacked(suffix);
            FileWrapper parent = updatedSharer.getByPath(updatedSharer.username + "/" + folderName).get().get();
            parent.uploadFileSection(filename, suffixStream, false, originalFileContents.length,
                    originalFileContents.length + suffix.length, Optional.empty(), true,
                    updatedSharer.network, crypto, l -> {},
                    null).get();
            FileWrapper extendedFile = updatedSharer.getByPath(originalFilePath).get().get();
            AsyncReader extendedContents = extendedFile.getInputStream(updatedSharer.network, updatedSharer.crypto, l -> {
            }).get();
            byte[] newFileContents = Serialize.readFully(extendedContents, extendedFile.getSize()).get();

            Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));

            // test remaining users can still see shared file and folder
            for (int j = i + 1; j < updatedSharees.size(); j++) {
                UserContext otherUser = updatedSharees.get(j);

                Optional<FileWrapper> sharedFolder = otherUser.getByPath(updatedSharer.username + "/" + folderName).get();
                Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getName().equals(folderName));

                FileWrapper sharedFile = otherUser.getByPath(updatedSharer.username + "/" + folderName + "/" + filename).get().get();
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
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).join();
        Path dirPath = Paths.get(sharer.username, folderName);

        FileWrapper folder = sharer.getByPath(dirPath).join().get();
        String filename = "somefile.txt";
        byte[] data = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(data);
        folder.uploadOrReplaceFile(filename, resetableFileInputStream,
                data.length, sharer.network, crypto, l -> {}, crypto.random.randomBytes(32)).join();
        String originalFilePath = sharer.username + "/" + folderName + "/" + filename;

        for (int i=0; i< 20; i++) {
            sharer.getByPath(dirPath).join().get()
                    .mkdir("subdir"+i, sharer.network, false, crypto).join();
        }

        // share /u1/folder with 'a'
        sharer.shareWriteAccessWith(dirPath, Collections.singleton(a.username)).join();

        // create a directory
        FileWrapper sharedFolderv1 = sharer.getByPath(dirPath).join().get();
        String subdirName = "subdir";
        sharedFolderv1.mkdir(subdirName, sharer.network, false, crypto).join();

        // share /u1/folder with 'b'
        Path subdirPath = Paths.get(sharer.username, folderName, subdirName);
        sharer.shareWriteAccessWith(subdirPath, Collections.singleton(b.username)).join();

        // check 'b' can upload a file
        UserContext shareeUploader = shareeUsers.get(0);
        FileWrapper sharedDir = shareeUploader.getByPath(subdirPath).join().get();
        sharedDir.uploadFileJS("a-new-file.png", AsyncReader.build(data), 0, data.length,
                false, false, shareeUploader.network, crypto, x -> {}, shareeUploader.getTransactionService()).join();

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
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, password, e.network, crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);
                    }
                }).collect(Collectors.toList());

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
                null).join();
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
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).join();
        Path dirPath = Paths.get(sharer.username, folderName);

        // create a directory
        String subdirName = "subdir";
        sharer.getByPath(dirPath).join().get()
                .mkdir(subdirName, sharer.network, false, crypto).join();

        // share /u1/folder/subdir with 'b'
        Path subdirPath = Paths.get(sharer.username, folderName, subdirName);
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
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).join();
        Path dirPath = Paths.get(sharer.username, folderName);


        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = sharer.crypto.random.randomBytes(1*1024*1024);
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);

        FileWrapper dir = sharer.getByPath(dirPath).join().get();

        FileWrapper uploaded = dir.uploadOrReplaceFile(filename, resetableFileInputStream, f.length(),
                sharer.network, crypto, l -> {}, crypto.random.randomBytes(32)).join();


        Path fileToShare = Paths.get(sharer.username, folderName, filename);
        sharer.shareReadAccessWith(fileToShare
                , Collections.singleton(a.username)).join();

        // check 'a' can see the shared file
        FileWrapper sharedFolder = a.getByPath(sharer.username + "/" + folderName + "/" + filename).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, filename);


        sharer.unShareReadAccess(fileToShare, a.username).join();
        // check 'a' can't see the shared directory
        FileWrapper unsharedLocation = a.getByPath(sharer.username).join().get();
        Set<FileWrapper> children = unsharedLocation.getChildren(crypto.hasher, sharer.network).join();
        Assert.assertTrue("a can't see unshared folder", children.stream().filter(c -> c.getName().equals(folderName)).findFirst().isEmpty());
    }

    public static void grantAndRevokeWriteThenReadAccessToFolder(NetworkAccess network, Random random) throws IOException {
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
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).join();
        Path dirPath = Paths.get(sharer.username, folderName);

        // share /u1/folder with 'a'
        sharer.shareWriteAccessWith(dirPath, Collections.singleton(a.username)).join();

        // check 'a' can see the shared file
        FileWrapper sharedFolder = a.getByPath(sharer.username + "/" + folderName).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

        sharer.unShareWriteAccess(dirPath, a.username).join();

        // check 'a' can't see the shared directory
        FileWrapper unsharedLocation = a.getByPath(sharer.username).join().get();
        Set<FileWrapper> children = unsharedLocation.getChildren(crypto.hasher, sharer.network).join();
        Assert.assertTrue("a can't see unshared folder", children.stream().filter(c -> c.getName().equals(folderName)).findFirst().isEmpty());


        sharer.shareReadAccessWith(dirPath
                , Collections.singleton(a.username)).join();

        // check 'a' can see the shared file
        sharedFolder = a.getByPath(sharer.username + "/" + folderName).join()
                .orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
        Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);


        sharer.unShareReadAccess(dirPath, a.username).join();
        // check 'a' can't see the shared directory
        unsharedLocation = a.getByPath(sharer.username).join().get();
        children = unsharedLocation.getChildren(crypto.hasher, sharer.network).join();
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
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).join();
        Path dirPath = Paths.get(sharer.username, folderName);

        // put a file and some sub-dirs into the dir
        FileWrapper folder = sharer.getByPath(dirPath).join().get();
        String filename = "somefile.txt";
        byte[] data = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(data);
        folder.uploadOrReplaceFile(filename, resetableFileInputStream,
                data.length, sharer.network, crypto, l -> {}, crypto.random.randomBytes(32)).join();

        for (int i=0; i< 20; i++) {
            sharer.getByPath(dirPath).join().get()
                    .mkdir("subdir"+i, sharer.network, false, crypto).join();
        }

        // grant write access to a directory to user 'a'
        sharer.shareWriteAccessWith(dirPath, Collections.singleton(a.username)).join();

        // create another sub-directory
        FileWrapper sharedFolderv1 = sharer.getByPath(dirPath).join().get();
        String subdirName = "subdir";
        sharedFolderv1.mkdir(subdirName, sharer.network, false, crypto).join();

        // grant write access to a sub-directory to user 'b'
        Path subdirPath = Paths.get(sharer.username, folderName, subdirName);
        sharer.shareWriteAccessWith(subdirPath, Collections.singleton(b.username)).join();

        List<Set<AbsoluteCapability>> childCapsByChunk0 = getAllChildCapsByChunk(sharer.getByPath(dirPath).join().get(), network);
        Assert.assertTrue("Correct links per chunk, without duplicates",
                childCapsByChunk0.stream().map(x -> x.size()).collect(Collectors.toList())
                        .equals(Arrays.asList(10, 10, 2)));

        // check 'b' can upload a file
        UserContext shareeUploader = shareeUsers.get(0);
        FileWrapper sharedDir = shareeUploader.getByPath(subdirPath).join().get();
        sharedDir.uploadFileJS("a-new-file.png", AsyncReader.build(data), 0, data.length,
                false, false, shareeUploader.network, crypto, x -> {}, shareeUploader.getTransactionService()).join();

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
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, password, e.network, crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);
                    }
                }).collect(Collectors.toList());

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
        FileWrapper u1Root = sharer.getUserRoot().join();

        String filename = "somefile.txt";
        byte[] fileData = sharer.crypto.random.randomBytes(1*1024*1024);
        Path file1 = Paths.get(sharer.username, "first-file.txt");
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
        Assert.assertTrue(item.path.equals("/" + file1.toString()));

        // Test the feed after a fresh login
        UserContext freshA = PeergosNetworkUtils.ensureSignedUp(a.username, password, network, crypto);
        SocialFeed freshFeed = freshA.getSocialFeed().join();
        List<SharedItem> freshItems = freshFeed.getShared(feedSize, feedSize + 1, a.crypto, a.network).join();
        Assert.assertTrue(freshItems.size() > 0);
        SharedItem freshItem = freshItems.get(0);
        Assert.assertTrue(freshItem.equals(item));

        // Test sharing a new item after construction
        Path file2 = Paths.get(sharer.username, "second-file.txt");
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
        Path file3 = Paths.get(sharer.username, "third-file.txt");
        uploadAndShare(fileData, file3, sharer, a.username);

        // browse to sender home
        freshA.getByPath(Paths.get(sharer.username)).join();

        Path file4 = Paths.get(sharer.username, "fourth-file.txt");
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
        SocialPost post = new SocialPost(SocialPost.Type.Text, sharer.username, "G'day, skip!", Arrays.asList("WELCOME"), LocalDateTime.now(),
                SocialPost.Resharing.Friends, Optional.empty(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
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

    private static void uploadAndShare(byte[] data, Path file, UserContext sharer, String sharee) {
        String filename = file.getFileName().toString();
        sharer.getByPath(file.getParent()).join().get()
                .uploadOrReplaceFile(filename, AsyncReader.build(data), data.length,
                        sharer.network, crypto, l -> {}, crypto.random.randomBytes(32)).join();
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
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.crypto).join();
        sharer.getUserRoot().join().mkdir(dir2, sharer.network, false, sharer.crypto).join();

        Path dirToShare1 = Paths.get(sharer.username, dir1);
        Path dirToShare2 = Paths.get(sharer.username, dir2);
        sharer.shareReadAccessWith(dirToShare1, Set.of(sharee.username)).join();
        sharer.shareReadAccessWith(dirToShare2, Set.of(sharee.username)).join();

        SocialFeed feed = sharee.getSocialFeed().join();
        List<SharedItem> items = feed.getShared(0, 1000, sharee.crypto, sharee.network).join();
        Assert.assertTrue(items.size() == 2 + 2);

        sharee.getUserRoot().join().mkdir("mine", sharee.network, false, sharer.crypto).join();
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
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.crypto).join();
        sharer.getUserRoot().join().mkdir(dir2, sharer.network, false, sharer.crypto).join();

        Path dirToShare1 = Paths.get(sharer.username, dir1);
        Path dirToShare2 = Paths.get(sharer.username, dir2);
        sharer.shareReadAccessWith(dirToShare1, Set.of(sharee.username)).join();
        sharer.shareReadAccessWith(dirToShare2, Set.of(sharee.username)).join();

        SocialFeed feed = sharee.getSocialFeed().join();
        int initialFeedSize = 2;
        List<SharedItem> items = feed.getShared(0, 1000, sharee.crypto, sharee.network).join();
        Assert.assertTrue(items.size() == initialFeedSize + 2);

        sharee = PeergosNetworkUtils.ensureSignedUp(sharee.username, password, network, crypto);
        sharee.getUserRoot().join().mkdir("mine", sharer.network, false, sharer.crypto).join();

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
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.crypto).join();
        sharer.getUserRoot().join().mkdir(dir2, sharer.network, false, sharer.crypto).join();

        Path dirToShare1 = Paths.get(sharer.username, dir1);
        Path dirToShare2 = Paths.get(sharer.username, dir2);
        sharer.shareReadAccessWith(dirToShare1, Set.of(a.username)).join();
        sharer.shareReadAccessWith(dirToShare2, Set.of(a.username)).join();

        SocialFeed feed = a.getSocialFeed().join();
        int initialFeedSize = 2;
        List<SharedItem> items = feed.getShared(0, 1000, a.crypto, a.network).join();
        Assert.assertTrue(items.size() == initialFeedSize + 2);

        //Add another file and share
        String dir3 = "three";
        sharer.getUserRoot().join().mkdir(dir3, sharer.network, false, sharer.crypto).join();

        Path dirToShare3 = Paths.get(sharer.username, dir3);
        sharer.shareReadAccessWith(dirToShare3, Set.of(a.username)).join();

        feed = a.getSocialFeed().join().update().join();
        items = feed.getShared(0, 1000, a.crypto, a.network).join();
        Assert.assertTrue(items.size() == initialFeedSize + 3);
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
        sharer.getUserRoot().join().mkdir(dirName, sharer.network, false, sharer.crypto).join();

        Path dirToShare1 = Paths.get(sharer.username, dirName);
        SocialState social = sharer.getSocialState().join();
        String friends = social.getFriendsGroupUid();
        sharer.shareReadAccessWith(dirToShare1, Set.of(friends)).join();

        FileSharedWithState fileSharedWithState = sharer.sharedWith(dirToShare1).join();
        Assert.assertTrue(fileSharedWithState.readAccess.size() == 1);
        Assert.assertTrue(fileSharedWithState.readAccess.contains(friends));

        Optional<FileWrapper> dir = friend.getByPath(dirToShare1).join();
        Assert.assertTrue(dir.isPresent());

        Optional<FileWrapper> home = friend.getByPath(Paths.get(sharer.username)).join();
        Assert.assertTrue(home.isPresent());

        Optional<FileWrapper> dirViaGetChild = home.get().getChild(dirName, sharer.crypto.hasher, sharer.network).join();
        Assert.assertTrue(dirViaGetChild.isPresent());

        Set<FileWrapper> children = home.get().getChildren(sharer.crypto.hasher, sharer.network).join();
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
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.crypto).join();

        Path dirToShare1 = Paths.get(sharer.username, dir1);
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
        sharer.getUserRoot().join().mkdir(dirName, sharer.network, false, sharer.crypto).join();

        Path dirToShare1 = Paths.get(sharer.username, dirName);
        SocialState social = sharer.getSocialState().join();
        String friends = social.getFriendsGroupUid();
        sharer.shareReadAccessWith(dirToShare1, Set.of(friends)).join();

        FileSharedWithState fileSharedWithState = sharer.sharedWith(dirToShare1).join();
        Assert.assertTrue(fileSharedWithState.readAccess.size() == 1);
        Assert.assertTrue(fileSharedWithState.readAccess.contains(friends));

        sharer.shareWriteAccessWith(dirToShare1, Set.of(friend.username)).join();

        Optional<FileWrapper> dir = friend.getByPath(dirToShare1).join();
        Assert.assertTrue(dir.isPresent() && dir.get().isWritable());

        Optional<FileWrapper> home = friend.getByPath(Paths.get(sharer.username)).join();
        Assert.assertTrue(home.isPresent());

        Optional<FileWrapper> dirViaGetChild = home.get().getChild(dirName, sharer.crypto.hasher, sharer.network).join();
        Assert.assertTrue(dirViaGetChild.isPresent() && dirViaGetChild.get().isWritable());
    }

    public static void groupAwareSharing(NetworkAccess network, Random random,
                                         TriFunction<UserContext, Path, Set<String>, CompletableFuture<Boolean>> shareFunction,
                                         TriFunction<UserContext, Path, Set<String>, CompletableFuture<Boolean>> unshareFunction,
                                         TriFunction<UserContext, Path, FileSharedWithState, Integer> resultFunc) {
        CryptreeNode.setMaxChildLinkPerBlob(10);
        String password = "notagoodone";
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);
        UserContext shareeFriend = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);
        UserContext shareeFollower = PeergosNetworkUtils.ensureSignedUp(generateUsername(random), password, network, crypto);

        followBetweenGroups(Arrays.asList(sharer), Arrays.asList(shareeFollower));
        friendBetweenGroups(Arrays.asList(sharer), Arrays.asList(shareeFriend));

        String dir1 = "one";
        sharer.getUserRoot().join().mkdir(dir1, sharer.network, false, sharer.crypto).join();
        Path dirToShare1 = Paths.get(sharer.username, dir1);
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

        AbsoluteCapability nextChunkCap = cap.withMapKey(dir.getNextChunkLocation(cap.rBaseKey, Optional.empty(), cap.getMapKey(), null).join());

        Snapshot version = new Snapshot(cap.writer,
                WriterData.getWriterData(network.mutable.getPointerTarget(cap.owner, cap.writer,
                        network.dhtClient).join().get(), network.dhtClient).join());

        Optional<CryptreeNode> next = network.getMetadata(version.get(nextChunkCap.writer).props, nextChunkCap).join();
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
            return dir.getAllChildrenCapabilities(new Snapshot(cap.writer,
                    WriterData.getWriterData(network.mutable.getPointerTarget(cap.owner, cap.writer,
                            network.dhtClient).join().get(), network.dhtClient).join()), cap, crypto.hasher, network).join()
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
        FileWrapper u1Root = sharer.getUserRoot().get();
        String folderName = "awritefolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto).get();
        String path = Paths.get(sharerUsername, folderName).toString();
        System.out.println("PATH "+ path);

        // file is uploaded, do the actual sharing
        boolean finished = sharer.shareWriteAccessWith(Paths.get(path),
                shareeUsers.stream()
                        .map(c -> c.username)
                        .collect(Collectors.toSet())).get();

        // check each user can see the shared folder, and write to it
        for (UserContext sharee : shareeUsers) {
            FileWrapper sharedFolder = sharee.getByPath(sharer.username + "/" + folderName).get().orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
            Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

            sharedFolder.mkdir(sharee.username, shareeNode, false, crypto).get();
        }

        Set<FileWrapper> children = sharer.getByPath(path).get().get().getChildren(crypto.hasher, sharerNode).get();
        Assert.assertTrue(children.size() == shareeCount);
    }

    public static void publicLinkToFile(Random random, NetworkAccess writerNode, NetworkAccess readerNode) throws Exception {
        String username = generateUsername(random);
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, writerNode, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[128*1024];
        random.nextBytes(data);
        long t1 = System.currentTimeMillis();
        userRoot.uploadFileSection(filename, new AsyncReader.ArrayBacked(data), false, 0, data.length, Optional.empty(),
                true, context.network, crypto, l -> {}, crypto.random.randomBytes(32)).get();
        long t2 = System.currentTimeMillis();
        String path = "/" + username + "/" + filename;
        FileWrapper file = context.getByPath(path).get().get();
        String link = file.toLink();
        UserContext linkContext = UserContext.fromSecretLink(link, readerNode, crypto).get();
        String entryPath = linkContext.getEntryPath().join();
        Assert.assertTrue("Correct entry path", entryPath.equals("/" + username));
        Optional<FileWrapper> fileThroughLink = linkContext.getByPath(path).get();
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
                // send intial request
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
            return UserContext.signIn(username, password, network, crypto).join();
        return UserContext.signUp(username, password, "", network, crypto).join();
    }
}
