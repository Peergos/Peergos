package peergos.server.util;

import org.junit.Assert;
import peergos.server.storage.ResetableFileInputStream;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.social.*;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Serialize;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PeergosNetworkUtils {

    public static String generateUsername(Random random) {
        return "username_" + Math.abs(random.nextInt() % 1_000_000);
    }

    public static final Crypto crypto = Crypto.initJava();

    public static String randomString() {
        return UUID.randomUUID().toString();
    }

    public static byte[] randomData(Random random, int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    public static void checkFileContents(byte[] expected, FileWrapper f, UserContext context) throws Exception {
        long size = f.getFileProperties().size;
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context.network, context.crypto.random,
                size, l -> {
                }).get(), f.getSize()).get();
        assertEquals(expected.length, size);
        assertTrue("Correct contents", Arrays.equals(retrievedData, expected));
    }

    public static List<UserContext> getUserContextsForNode(NetworkAccess network, Random random, int size) {
        return IntStream.range(0, size)
                .mapToObj(e -> {
                    String username = generateUsername(random);
                    try {
                        return ensureSignedUp(username, username, network.clear(), crypto);
                    } catch (Exception ioe) {
                        throw new IllegalStateException(ioe);
                    }
                }).collect(Collectors.toList());
    }


    public static void grantAndRevokeFileReadAccess(NetworkAccess sharerNode, NetworkAccess shareeNode, int shareeCount, Random random) throws Exception {
        Assert.assertTrue(0 < shareeCount);
        //sign up a user on sharerNode

        String sharerUsername = generateUsername(random);
        UserContext sharerUser = ensureSignedUp(sharerUsername, sharerUsername, sharerNode.clear(), crypto);

        //sign up some users on shareeNode
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount);

        // send follow requests from sharees to sharer
        for (UserContext userContext : shareeUsers) {
            userContext.sendFollowRequest(sharerUser.username, SymmetricKey.random()).get();
        }

        // make sharer reciprocate all the follow requests
        List<FollowRequestWithCipherText> sharerRequests = sharerUser.processFollowRequests().get();
        for (FollowRequestWithCipherText u1Request : sharerRequests) {
            AbsoluteCapability pointer = u1Request.req.entry.get().pointer;
            Assert.assertTrue("Read only capabilities are shared", ! pointer.wBaseKey.isPresent());
            boolean accept = true;
            boolean reciprocate = true;
            sharerUser.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        for (UserContext userContext : shareeUsers) {
            userContext.processFollowRequests().get();//needed for side effect
        }

        // upload a file to "a"'s space
        FileWrapper u1Root = sharerUser.getUserRoot().get();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = sharerUser.crypto.random.randomBytes(10*1024*1024);
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);
        FileWrapper uploaded = u1Root.uploadOrOverwriteFile(filename, resetableFileInputStream, f.length(),
                sharerUser.network, crypto.random, crypto.hasher, l -> {},
                u1Root.generateChildLocationsFromSize(originalFileContents.length, crypto.random)).get();

        // share the file from sharer to each of the sharees
        FileWrapper u1File = sharerUser.getByPath(sharerUser.username + "/" + filename).get().get();
        sharerUser.shareReadAccessWith(Paths.get(sharerUser.username, filename), shareeUsers.stream().map(u -> u.username).collect(Collectors.toSet())).get();

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
            Set<FileWrapper> children = friendRoot.get().getChildren(userContext.network).get();
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
                        return ensureSignedUp(e.username, e.username, shareeNode, crypto);
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

        UserContext updatedSharerUser = ensureSignedUp(sharerUsername, sharerUsername, sharerNode.clear(), crypto);

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
                Optional.empty(), true, updatedSharerUser.network, crypto.random, crypto.hasher, l -> {},
                null).get();
        AsyncReader extendedContents = updatedSharerUser.getByPath(sharerUser.username + "/" + filename).get().get().getInputStream(updatedSharerUser.network,
                crypto.random, l -> {}).get();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).get();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));
    }

    public static void grantAndRevokeFileWriteAccess(NetworkAccess sharerNode, NetworkAccess shareeNode, int shareeCount, Random random) throws Exception {
        Assert.assertTrue(0 < shareeCount);
        //sign up a user on sharerNode

        String sharerUsername = generateUsername(random);
        UserContext sharerUser = ensureSignedUp(sharerUsername, sharerUsername, sharerNode.clear(), crypto);

        //sign up some users on shareeNode
        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount);

        // send follow requests from sharees to sharer
        for (UserContext userContext : shareeUsers) {
            userContext.sendFollowRequest(sharerUser.username, SymmetricKey.random()).get();
        }

        // make sharer reciprocate all the follow requests
        List<FollowRequestWithCipherText> sharerRequests = sharerUser.processFollowRequests().get();
        for (FollowRequestWithCipherText u1Request : sharerRequests) {
            AbsoluteCapability pointer = u1Request.req.entry.get().pointer;
            Assert.assertTrue("Read only capabilities are shared", ! pointer.wBaseKey.isPresent());
            boolean accept = true;
            boolean reciprocate = true;
            sharerUser.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        // complete the friendship connection
        for (UserContext userContext : shareeUsers) {
            userContext.processFollowRequests().get();//needed for side effect
        }

        // upload a file to "a"'s space
        FileWrapper u1Root = sharerUser.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = sharerUser.crypto.random.randomBytes(10*1024*1024);
        AsyncReader resetableFileInputStream = AsyncReader.build(originalFileContents);
        FileWrapper uploaded = u1Root.uploadOrOverwriteFile(filename, resetableFileInputStream, originalFileContents.length,
                sharerUser.network, crypto.random, crypto.hasher, l -> {},
                u1Root.generateChildLocationsFromSize(originalFileContents.length, crypto.random)).get();

        // share the file from sharer to each of the sharees
        FileWrapper u1File = sharerUser.getByPath(sharerUser.username + "/" + filename).get().get();
        sharerUser.shareWriteAccessWith(Paths.get(sharerUser.username, filename), shareeUsers.stream().map(u -> u.username).collect(Collectors.toSet())).get();

        // check other users can read the file
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(sharerUser.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());
            Assert.assertTrue("File is writable", sharedFile.get().isWritable());
            checkFileContents(originalFileContents, sharedFile.get(), userContext);
        }

        // check other users can browser to the friend's root
        for (UserContext userContext : shareeUsers) {
            Optional<FileWrapper> friendRoot = userContext.getByPath(sharerUser.username).get();
            assertTrue("friend root present", friendRoot.isPresent());
            Set<FileWrapper> children = friendRoot.get().getChildren(userContext.network).get();
            Optional<FileWrapper> sharedFile = children.stream()
                    .filter(file -> file.getName().equals(filename))
                    .findAny();
            assertTrue("Shared file present via root.getChildren()", sharedFile.isPresent());
        }

        UserContext userToUnshareWith = shareeUsers.stream().findFirst().get();

        // unshare with a single user
        sharerUser.unShareWriteAccess(Paths.get(sharerUser.username, filename), userToUnshareWith.username).get();

        List<UserContext> updatedShareeUsers = shareeUsers.stream()
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, e.username, shareeNode, crypto);
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

        UserContext updatedSharerUser = ensureSignedUp(sharerUsername, sharerUsername, sharerNode.clear(), crypto);

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
                Optional.empty(), true, updatedSharerUser.network, crypto.random, crypto.hasher, l -> {},
                null).get();
        AsyncReader extendedContents = updatedSharerUser.getByPath(sharerUser.username + "/" + filename).get().get().getInputStream(updatedSharerUser.network,
                updatedSharerUser.crypto.random, l -> {}).get();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).get();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));
    }

    public static void grantAndRevokeDirReadAccess(NetworkAccess sharerNode, NetworkAccess shareeNode, int shareeCount, Random random) throws Exception {
        Assert.assertTrue(0 < shareeCount);
        CryptreeNode.setMaxChildLinkPerBlob(10);

        String sharerUsername = generateUsername(random);
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerUsername, sharerNode, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount);

        for (UserContext sharee : shareeUsers)
            sharee.sendFollowRequest(sharer.username, SymmetricKey.random()).get();

        List<FollowRequestWithCipherText> sharerRequests = sharer.processFollowRequests().get();
        for (FollowRequestWithCipherText u1Request : sharerRequests) {
            boolean accept = true;
            boolean reciprocate = true;
            sharer.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        for (UserContext user : shareeUsers) {
            user.processFollowRequests().get();
        }

        // friends are now connected
        // share a file from u1 to the others
        FileWrapper u1Root = sharer.getUserRoot().get();
        String folderName = "afolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto.random, crypto.hasher).get();
        String path = Paths.get(sharerUsername, folderName).toString();
        System.out.println("PATH "+ path);
        FileWrapper folder = sharer.getByPath(path).get().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(originalFileContents);
        FileWrapper updatedFolder = folder.uploadOrOverwriteFile(filename, resetableFileInputStream,
                originalFileContents.length, sharer.network, crypto.random, crypto.hasher, l -> {},
                folder.generateChildLocationsFromSize(originalFileContents.length, crypto.random)).get();
        String originalFilePath = sharer.username + "/" + folderName + "/" + filename;

        for (int i=0; i< 20; i++) {
            sharer.getByPath(path).join().get()
                    .mkdir("subdir"+i, sharer.network, false, crypto.random, crypto.hasher).join();
        }

        Set<String> childNames = sharer.getByPath(path).join().get().getChildren(sharer.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        // file is uploaded, do the actual sharing
        boolean finished = sharer.shareReadAccessWithAll(updatedFolder, shareeUsers.stream().map(c -> c.username).collect(Collectors.toSet())).get();

        // check each user can see the shared folder and directory
        for (UserContext sharee : shareeUsers) {

            FileWrapper sharedFolder = sharee.getByPath(sharer.username + "/" + folderName).get().orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
            Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

            FileWrapper sharedFile = sharee.getByPath(sharer.username + "/" + folderName + "/" + filename).get().get();
            checkFileContents(originalFileContents, sharedFile, sharee);
        }

        UserContext updatedSharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerUsername, sharerNode.clear(), crypto);

        List<UserContext> updatedSharees = shareeUsers.stream()
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, e.username, shareeNode, crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);
                    }
                }).collect(Collectors.toList());


        for (int i = 0; i < updatedSharees.size(); i++) {
            UserContext user = updatedSharees.get(i);
            sharer.unShareReadAccess(Paths.get(sharer.username, folderName), user.username).get();

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
                    updatedSharer.network, crypto.random, crypto.hasher, l -> {},
                    null).get();
            FileWrapper extendedFile = updatedSharer.getByPath(originalFilePath).get().get();
            AsyncReader extendedContents = extendedFile.getInputStream(updatedSharer.network, crypto.random, l -> {}).get();
            byte[] newFileContents = Serialize.readFully(extendedContents, extendedFile.getSize()).get();

            Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));

            // test remaining users can still see shared file and folder
            for (int j = i + 1; j < updatedSharees.size(); j++) {
                UserContext otherUser = updatedSharees.get(j);

                Optional<FileWrapper> sharedFolder = otherUser.getByPath(sharer.username + "/" + folderName).get();
                Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getName().equals(folderName));

                FileWrapper sharedFile = otherUser.getByPath(sharer.username + "/" + folderName + "/" + filename).get().get();
                checkFileContents(newFileContents, sharedFile, otherUser);
                Set<String> sharedChildNames = sharedFolder.get().getChildren(otherUser.network).join()
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
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerUsername, sharerNode, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount);

        for (UserContext sharee : shareeUsers)
            sharee.sendFollowRequest(sharer.username, SymmetricKey.random()).get();

        List<FollowRequestWithCipherText> sharerRequests = sharer.processFollowRequests().get();
        for (FollowRequestWithCipherText u1Request : sharerRequests) {
            boolean accept = true;
            boolean reciprocate = true;
            sharer.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        for (UserContext user : shareeUsers) {
            user.processFollowRequests().get();
        }

        // friends are now connected
        // share a file from u1 to the others
        FileWrapper u1Root = sharer.getUserRoot().join();
        String folderName = "afolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto.random, crypto.hasher).get();
        String path = Paths.get(sharerUsername, folderName).toString();
        System.out.println("PATH "+ path);
        FileWrapper folder = sharer.getByPath(path).join().get();
        String filename = "somefile.txt";
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(originalFileContents);
        folder.uploadOrOverwriteFile(filename, resetableFileInputStream,
                originalFileContents.length, sharer.network, crypto.random, crypto.hasher, l -> {},
                folder.generateChildLocationsFromSize(originalFileContents.length, crypto.random)).join();
        String originalFilePath = sharer.username + "/" + folderName + "/" + filename;

        for (int i=0; i< 20; i++) {
            sharer.getByPath(path).join().get()
                    .mkdir("subdir"+i, sharer.network, false, crypto.random, crypto.hasher).join();
        }

        Set<String> childNames = sharer.getByPath(path).join().get().getChildren(sharer.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        // file is uploaded, do the actual sharing
        boolean finished = sharer.shareWriteAccessWithAll(sharer.getByPath(path).join().get(), sharer.getUserRoot().join(), shareeUsers.stream()
                .map(c -> c.username)
                .collect(Collectors.toSet())).get();

        // check each user can see the shared folder and directory
        for (UserContext sharee : shareeUsers) {

            FileWrapper sharedFolder = sharee.getByPath(sharer.username + "/" + folderName).get().orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
            Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

            FileWrapper sharedFile = sharee.getByPath(sharer.username + "/" + folderName + "/" + filename).get().get();
            checkFileContents(originalFileContents, sharedFile, sharee);
            Set<String> sharedChildNames = sharedFolder.getChildren(sharee.network).join()
                    .stream()
                    .map(f -> f.getName())
                    .collect(Collectors.toSet());
            Assert.assertTrue("Correct children", sharedChildNames.equals(childNames));
        }

        UserContext updatedSharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerUsername, sharerNode.clear(), crypto);

        List<UserContext> updatedSharees = shareeUsers.stream()
                .map(e -> {
                    try {
                        return ensureSignedUp(e.username, e.username, shareeNode, crypto);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex.getMessage(), ex);
                    }
                }).collect(Collectors.toList());


        for (int i = 0; i < updatedSharees.size(); i++) {
            UserContext user = updatedSharees.get(i);
            sharer.unShareWriteAccess(Paths.get(sharer.username, folderName), user.username).get();

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
                    updatedSharer.network, crypto.random, crypto.hasher, l -> {},
                    null).get();
            FileWrapper extendedFile = updatedSharer.getByPath(originalFilePath).get().get();
            AsyncReader extendedContents = extendedFile.getInputStream(updatedSharer.network, updatedSharer.crypto.random, l -> {
            }).get();
            byte[] newFileContents = Serialize.readFully(extendedContents, extendedFile.getSize()).get();

            Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));

            // test remaining users can still see shared file and folder
            for (int j = i + 1; j < updatedSharees.size(); j++) {
                UserContext otherUser = updatedSharees.get(j);

                Optional<FileWrapper> sharedFolder = otherUser.getByPath(sharer.username + "/" + folderName).get();
                Assert.assertTrue("Shared folder present via direct path", sharedFolder.isPresent() && sharedFolder.get().getName().equals(folderName));

                FileWrapper sharedFile = otherUser.getByPath(sharer.username + "/" + folderName + "/" + filename).get().get();
                checkFileContents(newFileContents, sharedFile, otherUser);
                Set<String> sharedChildNames = sharedFolder.get().getChildren(otherUser.network).join()
                        .stream()
                        .map(f -> f.getName())
                        .collect(Collectors.toSet());
                Assert.assertTrue("Correct children", sharedChildNames.equals(childNames));
            }
        }
    }

    public static void shareFolderForWriteAccess(NetworkAccess sharerNode, NetworkAccess shareeNode, int shareeCount, Random random) throws Exception {
        Assert.assertTrue(0 < shareeCount);

        String sharerUsername = generateUsername(random);
        UserContext sharer = PeergosNetworkUtils.ensureSignedUp(sharerUsername, sharerUsername, sharerNode, crypto);

        List<UserContext> shareeUsers = getUserContextsForNode(shareeNode, random, shareeCount);

        for (UserContext sharee : shareeUsers)
            sharee.sendFollowRequest(sharer.username, SymmetricKey.random()).get();

        List<FollowRequestWithCipherText> sharerRequests = sharer.processFollowRequests().get();
        for (FollowRequestWithCipherText u1Request : sharerRequests) {
            boolean accept = true;
            boolean reciprocate = true;
            sharer.sendReplyFollowRequest(u1Request, accept, reciprocate).get();
        }

        for (UserContext user : shareeUsers) {
            user.processFollowRequests().get();
        }

        // friends are now connected
        // share a directory from u1 to the others
        FileWrapper u1Root = sharer.getUserRoot().get();
        String folderName = "awritefolder";
        u1Root.mkdir(folderName, sharer.network, SymmetricKey.random(), false, crypto.random, crypto.hasher).get();
        String path = Paths.get(sharerUsername, folderName).toString();
        System.out.println("PATH "+ path);
        FileWrapper folder = sharer.getByPath(path).get().get();

        // file is uploaded, do the actual sharing
        boolean finished = sharer.shareWriteAccessWithAll(folder, sharer.getUserRoot().join(),
                shareeUsers.stream()
                        .map(c -> c.username)
                        .collect(Collectors.toSet())).get();

        // check each user can see the shared folder, and write to it
        for (UserContext sharee : shareeUsers) {
            FileWrapper sharedFolder = sharee.getByPath(sharer.username + "/" + folderName).get().orElseThrow(() -> new AssertionError("shared folder is present after sharing"));
            Assert.assertEquals(sharedFolder.getFileProperties().name, folderName);

            sharedFolder.mkdir(sharee.username, shareeNode, false, crypto.random, crypto.hasher).get();
        }

        Set<FileWrapper> children = sharer.getByPath(path).get().get().getChildren(sharerNode).get();
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
                true, context.network, crypto.random, crypto.hasher, l -> {},
                userRoot.generateChildLocationsFromSize(data.length, context.crypto.random)).get();
        long t2 = System.currentTimeMillis();
        String path = "/" + username + "/" + filename;
        FileWrapper file = context.getByPath(path).get().get();
        String link = file.toLink();
        UserContext linkContext = UserContext.fromPublicLink(link, readerNode, crypto).get();
        String entryPath = linkContext.getEntryPath().join();
        Assert.assertTrue("Correct entry path", entryPath.equals("/" + username));
        Optional<FileWrapper> fileThroughLink = linkContext.getByPath(path).get();
        Assert.assertTrue("File present through link", fileThroughLink.isPresent());
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return UserContext.ensureSignedUp(username, password, network, crypto).get();
    }
}
