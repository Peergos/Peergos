package peergos.server.tests;

import org.junit.*;
import peergos.server.storage.ResetableFileInputStream;
import peergos.server.util.Args;
import peergos.server.util.PeergosNetworkUtils;
import peergos.shared.crypto.hash.*;
import peergos.shared.fingerprint.*;
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
    private static UserService service;
    private Random random = new Random();
    private final NetworkAccess network;
    private static final Crypto crypto = Main.initCrypto();
    private final int userCount;

    public MultiUserTests() {
        this.userCount = 2;
        WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
        this.network = new NetworkAccess(service.coreNode, service.social, service.storage,
                service.mutable, mutableTree, synchronizer, service.controller, service.usage, Arrays.asList("peergos"), false);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args);
    }

    public static void checkUserValidity(NetworkAccess network, String username) {
        PublicKeyHash identity = network.coreNode.getPublicKeyHash(username).join().get();
        checkUserValidity(1, identity, identity, Collections.emptySet(), network);
    }

    public static void checkUserValidity(int maxClaims,
                                         PublicKeyHash owner,
                                         PublicKeyHash writer,
                                         Set<PublicKeyHash> ancestors,
                                         NetworkAccess network) {
        WriterData props = WriterData.getWriterData(owner, writer, network.mutable, network.dhtClient).join().props;
        if (! props.ownedKeys.isPresent())
            return;
        OwnedKeyChamp ownedChamp = props.getOwnedKeyChamp(network.dhtClient, network.hasher).join();
        Set<OwnerProof> empty = Collections.emptySet();
        Set<OwnerProof> claims = ownedChamp.applyToAllMappings(empty,
                (a, b) -> CompletableFuture.completedFuture(Stream.concat(a.stream(), Stream.of(b.right)).collect(Collectors.toSet())),
                network.dhtClient).join();
        Set<PublicKeyHash> ownedKeys = claims.stream()
                .map(p -> p.ownedKey)
                .collect(Collectors.toSet());
        Set<Pair<PublicKeyHash, PublicKeyHash>> pairs = claims.stream()
                .map(p -> new Pair<>(p.getOwner(network.dhtClient).join(), p.ownedKey))
                .collect(Collectors.toSet());
        Set<PublicKeyHash> ownerKeys = pairs.stream()
                .map(p -> p.left)
                .collect(Collectors.toSet());
        if (claims.size() > maxClaims)
            throw new IllegalStateException("Too many owned keys on identity key pair for " + writer);
        if (! ownerKeys.isEmpty() && ownerKeys.size() != 1)
            throw new IllegalStateException("More than 1 owner key on writer data for " + writer);
        if (! ownerKeys.isEmpty() && ! ownerKeys.contains(writer))
            throw new IllegalStateException("WriterData contains claims with wrong owner for " + writer);
        if (ownedKeys.contains(writer))
            throw new IllegalStateException("Identity key pair owns itself!");
        HashSet<PublicKeyHash> withCurrent = new HashSet<>(ancestors);
        withCurrent.add(writer);
        for (PublicKeyHash ownedKey : ownedKeys) {
            if (! withCurrent.contains(ownedKey))
                checkUserValidity(Integer.MAX_VALUE, owner, ownedKey, withCurrent, network);

        }
    }

    private List<UserContext> getUserContexts(int size, List<String> passwords) {
        return getUserContextsForNode(network, random, size, passwords);
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
    public void shareAFileWithDifferentSigner() {
        PeergosNetworkUtils.shareFileWithDifferentSigner(network, network, random);
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
    public void grantAndRevokeNestedDirWriteAccess() {
        PeergosNetworkUtils.grantAndRevokeNestedDirWriteAccess(network, random);
    }

    @Test
    public void grantAndRevokeParentNestedWriteAccess() {
        PeergosNetworkUtils.grantAndRevokeParentNestedWriteAccess(network, random);
    }

    @Test
    public void grantAndRevokeDirWriteAccessWithNestedWriteAccess() {
        PeergosNetworkUtils.grantAndRevokeDirWriteAccessWithNestedWriteAccess(network, random);
    }

    @Test
    public void grantAndRevokeReadAccessToFileInFolder() throws IOException{
        PeergosNetworkUtils.grantAndRevokeReadAccessToFileInFolder(network, random);
    }

    @Test
    public void grantAndRevokeWriteThenReadAccessToFolder() throws IOException{
        PeergosNetworkUtils.grantAndRevokeWriteThenReadAccessToFolder(network, random);
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

        // make u1 friend all users
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), Arrays.asList(u2));

        // upload a file to u1's space
        FileWrapper u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] data = UserTests.randomData(10*1024*1024);

        FileWrapper uploaded = u1Root.uploadOrOverwriteFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                u1.network, crypto, l -> {}, crypto.random.randomBytes(32)).get();

        // share the file from u1 to each of the others
        FileWrapper u1File = u1.getByPath(u1.username + "/" + filename).get().get();
        sharingFunction.apply(u1, u2, filename).get();

        // check other user can read the file
        FileWrapper sharedFile = u2.getByPath(u1.username + "/" + filename).get().get();
        String dirname = "adir";
        u2.getUserRoot().get().mkdir(dirname, network, false, crypto).get();
        FileWrapper targetDir = u2.getByPath(Paths.get(u2.username, dirname).toString()).get().get();

        // copy the friend's file to our own space, this should reupload the file encrypted with a new key
        // this prevents us exposing to the network our social graph by the fact that we pin the same file fragments
        sharedFile.copyTo(targetDir, u2).get();
        FileWrapper copy = u2.getByPath(Paths.get(u2.username, dirname, filename).toString()).get().get();

        // check that the copied file has the correct contents
        UserTests.checkFileContents(data, copy, u2);
        Assert.assertTrue("Different base key", ! copy.getPointer().capability.rBaseKey.equals(u1File.getPointer().capability.rBaseKey));
        Assert.assertTrue("Different metadata key", ! UserTests.getMetaKey(copy).equals(UserTests.getMetaKey(u1File)));
        Assert.assertTrue("Different data key", ! UserTests.getDataKey(copy).equals(UserTests.getDataKey(u1File)));
    }

    @Test
    public void revokeReadAccessToWritableFile() {

        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);
        UserContext u3 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);
        UserContext u4 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        List<UserContext> all = Arrays.asList(u1, u2, u3, u4);

        // make u1 friend all users
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), Arrays.asList(u2, u3, u4));

        u1.getUserRoot().join().mkdir("subdir", u1.network, false, crypto).join();
        byte[] fileData = "file data".getBytes();
        AsyncReader reader = AsyncReader.build(fileData);
        u1.getByPath(Paths.get(u1.username, "subdir")).join().get().uploadOrOverwriteFile("file.txt",
                reader, fileData.length, u1.network, crypto, x -> {}, u1.crypto.random.randomBytes(32)).join();
        Path filePath = Paths.get(u1.username, "subdir", "file.txt");
        FileWrapper file = u1.getByPath(filePath).join().get();
        u1.shareWriteAccessWith(filePath, Stream.of(u2.username).collect(Collectors.toSet())).join();
        u1.shareWriteAccessWith(filePath, Stream.of(u3.username).collect(Collectors.toSet())).join();
        u1.shareReadAccessWith(filePath, Stream.of(u4.username).collect(Collectors.toSet())).join();
        u1.unShareReadAccess(filePath, Stream.of(u4.username).collect(Collectors.toSet())).join();

        // check u1 can log in
        UserContext freshContext = PeergosNetworkUtils.ensureSignedUp(u1.username, "a", network.clear(), crypto);
        freshContext.getUserRoot().join().mkdir("Adir", network, false, crypto).join();
        checkUserValidity(network, u1.username);
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

        // send follow requests from each other user to u1
        List<String> shareePasswords = IntStream.range(0, 1)
                .mapToObj(i -> PeergosNetworkUtils.generatePassword())
                .collect(Collectors.toList());
        List<UserContext> userContexts = getUserContexts(1, shareePasswords);

        // make u1 friend all users
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), userContexts);

        // upload a file to u1's space
        FileWrapper u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] data1 = "Hello Peergos friend!".getBytes();
        AsyncReader file1Reader = new AsyncReader.ArrayBacked(data1);
        FileWrapper uploaded = u1Root.uploadOrOverwriteFile(filename, file1Reader, data1.length,
                u1.network, u1.crypto, l -> {}, crypto.random.randomBytes(32)).get();

        // upload a different file with the same name in a sub folder
        uploaded.mkdir("subdir", u1.network, false, crypto).get();
        FileWrapper subdir = u1.getByPath("/" + u1.username + "/subdir").get().get();
        byte[] data2 = "Goodbye Peergos friend!".getBytes();
        AsyncReader file2Reader = new AsyncReader.ArrayBacked(data2);
        subdir.uploadOrOverwriteFile(filename, file2Reader, data2.length,
                u1.network, u1.crypto, l -> {}, crypto.random.randomBytes(32)).get();

        // share the file from "a" to each of the others
        //        sharingFunction.apply(u1, u2, filenameu1.shareReadAccessWith(Paths.get(u1.username, filename), userContexts.stream().map(u -> u.username).collect(Collectors.toSet())).get();

        sharingFunction.apply(u1, userContexts, Paths.get(u1.username, filename)).get();

        sharingFunction.apply(u1, userContexts, Paths.get(u1.username, "subdir", filename)).get();

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(u1.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(userContext.network,
                    userContext.crypto, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(data1, fileContents));
        }

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            String expectedPath = Paths.get(u1.username, "subdir", filename).toString();
            Optional<FileWrapper> sharedFile = userContext.getByPath(expectedPath).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(userContext.network,
                    userContext.crypto, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(data2, fileContents));
        }
    }

    @Test
    public void deleteFileSharedWithWriteAccess() throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        // send follow requests from each other user to u1
        List<String> shareePasswords = IntStream.range(0, 1)
                .mapToObj(i -> PeergosNetworkUtils.generatePassword())
                .collect(Collectors.toList());
        List<UserContext> userContexts = getUserContexts(1, shareePasswords);

        // make u1 friend all users
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), userContexts);

        // upload a file to u1's space
        FileWrapper u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] data1 = "Hello Peergos friend!".getBytes();
        AsyncReader file1Reader = new AsyncReader.ArrayBacked(data1);
        String subdirName = "subdir";
        u1Root.mkdir(subdirName, u1.network, false, crypto).get();
        Path subdirPath = Paths.get(u1.username, subdirName);
        FileWrapper subdir = u1.getByPath(subdirPath).get().get();
        FileWrapper uploaded = subdir.uploadOrOverwriteFile(filename, file1Reader, data1.length,
                u1.network, u1.crypto, l -> {}, crypto.random.randomBytes(32)).get();

        Path filePath = Paths.get(u1.username, subdirName, filename);
        u1.shareWriteAccessWith(filePath, userContexts.stream().map(u -> u.username).collect(Collectors.toSet()));

        // check other users can read the file
        for (UserContext userContext : userContexts) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(filePath).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(userContext.network,
                    userContext.crypto, l -> {}).get();

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
                u1.network.mutable, u1.network.dhtClient, u1.network.hasher).join();
        Assert.assertTrue("New writer key present", keysOwnedByRootSigner.contains(theFile.writer()));

        AbsoluteCapability cap = theFile.getPointer().capability;
        Set<String> sharedWriteAccessWithBefore = u1.sharedWithCache.getSharedWith(SharedWithCache.Access.WRITE, cap);
        Assert.assertTrue("file shared", ! sharedWriteAccessWithBefore.isEmpty());

        theFile.remove(parentFolder, u1).get();
        Optional<FileWrapper> removedFile = u1.getByPath(filePath).get();
        Assert.assertTrue("file removed", ! removedFile.isPresent());

        Set<String> sharedWriteAccessWithAfter = u1.sharedWithCache.getSharedWith(SharedWithCache.Access.WRITE, cap);
        Assert.assertTrue("file shared", sharedWriteAccessWithAfter.isEmpty());

        for (UserContext userContext : userContexts) {
            Optional<FileWrapper> sharedFile = userContext.getByPath(filePath).get();
            Assert.assertTrue("shared file removed", ! sharedFile.isPresent());
        }
        Set<PublicKeyHash> updatedKeysOwnedByRootSigner = WriterData.getDirectOwnedKeys(theFile.owner(),
                parentFolder.writer(), u1.network.mutable, u1.network.dhtClient, u1.network.hasher).join();
        Assert.assertTrue("New writer key not present", ! updatedKeysOwnedByRootSigner.contains(theFile.writer()));
    }


    @Test
    public void renamedFileSharedWith() throws Exception {
        //read access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> readAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareReadAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));

        renamedFileSharedWith(readAccessSharingFunction, SharedWithCache.Access.READ);
        //write access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> writeAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareWriteAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));
        renamedFileSharedWith(writeAccessSharingFunction, SharedWithCache.Access.WRITE);
    }

    private void renamedFileSharedWith(
            TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> shareFunction,
            SharedWithCache.Access sharedWithAccess)
            throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        // send follow requests from each other user to u1
        List<String> shareePasswords = IntStream.range(0, 1)
                .mapToObj(i -> PeergosNetworkUtils.generatePassword())
                .collect(Collectors.toList());
        List<UserContext> userContexts = getUserContexts(1, shareePasswords);

        // make u1 friend all users
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), userContexts);

        // upload a file to u1's space
        FileWrapper u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        byte[] data1 = "Hello Peergos friend!".getBytes();
        AsyncReader file1Reader = new AsyncReader.ArrayBacked(data1);
        String subdirName = "subdir";
        u1Root.mkdir(subdirName, u1.network, false, crypto).get();
        Path subdirPath = Paths.get(u1.username, subdirName);
        FileWrapper subdir = u1.getByPath(subdirPath).get().get();
        FileWrapper uploaded = subdir.uploadOrOverwriteFile(filename, file1Reader, data1.length,
                u1.network, u1.crypto, l -> {}, crypto.random.randomBytes(32)).get();

        Path filePath = Paths.get(u1.username, subdirName, filename);

        shareFunction.apply(u1, userContexts, filePath).join();

        //rename file
        FileWrapper theFile = u1.getByPath(filePath).get().get();
        FileWrapper parentFolder = u1.getByPath(subdirPath).get().get();

        AbsoluteCapability cap = theFile.getPointer().capability;
        Set<String> sharedAccessWithBefore = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("file shared", ! sharedAccessWithBefore.isEmpty());

        String newFilename = "newfilename.txt";
        theFile.rename(newFilename, parentFolder, false, u1).get();

        filePath = Paths.get(u1.username, subdirName, newFilename);
        theFile = u1.getByPath(filePath).get().get();
        cap = theFile.getPointer().capability;

        Set<String> sharedAccessWithAfter = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("file shared", ! sharedAccessWithAfter.isEmpty());
    }

    @Test
    public void renamedDirectorySharedWith() throws Exception {
        //read access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> readAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareReadAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));

        renamedDirectorySharedWith(readAccessSharingFunction, SharedWithCache.Access.READ);
        //write access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> writeAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareWriteAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));
        renamedDirectorySharedWith(writeAccessSharingFunction, SharedWithCache.Access.WRITE);
    }

    private void renamedDirectorySharedWith(
            TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> shareFunction,
            SharedWithCache.Access sharedWithAccess)
            throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        // send follow requests from each other user to u1
        List<String> shareePasswords = IntStream.range(0, 1)
                .mapToObj(i -> PeergosNetworkUtils.generatePassword())
                .collect(Collectors.toList());
        List<UserContext> userContexts = getUserContexts(1, shareePasswords);

        // make u1 friend all users
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), userContexts);

        // upload a file to u1's space
        FileWrapper u1Root = u1.getUserRoot().get();
        String subdirName = "subdir";
        u1Root.mkdir(subdirName, u1.network, false, crypto).get();
        Path subdirPath = Paths.get(u1.username, subdirName);

        Path filePath = Paths.get(u1.username, subdirName);

        shareFunction.apply(u1, userContexts, filePath).join();

        //rename directory
        FileWrapper theDir = u1.getByPath(filePath).get().get();
        FileWrapper parentFolder = u1.getUserRoot().get();

        AbsoluteCapability cap = theDir.getPointer().capability;
        Set<String> sharedAccessWithBefore = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("directory shared", ! sharedAccessWithBefore.isEmpty());

        String newDirectoryName = "newDir";
        theDir.rename(newDirectoryName, parentFolder, false, u1).get();

        filePath = Paths.get(u1.username, newDirectoryName);
        theDir = u1.getByPath(filePath).get().get();
        cap = theDir.getPointer().capability;

        Set<String> sharedAccessWithAfter = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("directory shared", ! sharedAccessWithAfter.isEmpty());
    }

    @Test
    public void copyToFileSharedWith() throws Exception {
        //read access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> readAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareReadAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));

        copyToFileSharedWith(readAccessSharingFunction, SharedWithCache.Access.READ);
        //write access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> writeAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareWriteAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));
        copyToFileSharedWith(writeAccessSharingFunction, SharedWithCache.Access.WRITE);
    }

    private void copyToFileSharedWith(
            TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> shareFunction,
            SharedWithCache.Access sharedWithAccess)
            throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        // send follow requests from each other user to u1
        List<String> shareePasswords = IntStream.range(0, 1)
                .mapToObj(i -> PeergosNetworkUtils.generatePassword())
                .collect(Collectors.toList());
        List<UserContext> userContexts = getUserContexts(1, shareePasswords);

        // make u1 friend all users
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), userContexts);

        // upload a file to u1's space
        String filename = "somefile.txt";
        byte[] data1 = "Hello Peergos friend!".getBytes();
        AsyncReader file1Reader = new AsyncReader.ArrayBacked(data1);
        String subdirName = "subdir";
        String destinationSubdirName = "destdir";
        u1.getUserRoot().get().mkdir(subdirName, u1.network, false, crypto).get();
        u1.getUserRoot().get().mkdir(destinationSubdirName, u1.network, false, crypto).get();
        Path subdirPath = Paths.get(u1.username, subdirName);
        FileWrapper subdir = u1.getByPath(subdirPath).get().get();
        FileWrapper uploaded = subdir.uploadOrOverwriteFile(filename, file1Reader, data1.length,
                u1.network, u1.crypto, l -> {}, crypto.random.randomBytes(32)).get();

        Path filePath = Paths.get(u1.username, subdirName, filename);

        shareFunction.apply(u1, userContexts, filePath);

        FileWrapper theFile = u1.getByPath(filePath).get().get();
        AbsoluteCapability cap = theFile.getPointer().capability;
        Set<String> sharedWriteAccessWithBefore = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("file shared", ! sharedWriteAccessWithBefore.isEmpty());

        //copy file
        Path destSubdirPath = Paths.get(u1.username, destinationSubdirName);
        FileWrapper destSubdir = u1.getByPath(destSubdirPath).get().get();
        theFile.copyTo(destSubdir, u1);

        //old copy should retain sharedWith entries
        Set<String> sharedWriteAccessWithOriginal = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("file shared", ! sharedWriteAccessWithOriginal.isEmpty());

        filePath = Paths.get(u1.username, destinationSubdirName, filename);
        theFile = u1.getByPath(filePath).get().get();
        cap = theFile.getPointer().capability;

        Set<String> sharedWriteAccessWithNewCopy = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("file shared", sharedWriteAccessWithNewCopy.isEmpty());
    }

    @Test
    public void copyToDirectorySharedWith() throws Exception {
        //read access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> readAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareReadAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));

        copyToDirectorySharedWith(readAccessSharingFunction, SharedWithCache.Access.READ);
        //write access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> writeAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareWriteAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));
        copyToDirectorySharedWith(writeAccessSharingFunction, SharedWithCache.Access.WRITE);
    }

    private void copyToDirectorySharedWith(
            TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> shareFunction,
            SharedWithCache.Access sharedWithAccess)
            throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        List<String> shareePasswords = IntStream.range(0, 1)
                .mapToObj(i -> PeergosNetworkUtils.generatePassword())
                .collect(Collectors.toList());
        List<UserContext> userContexts = getUserContexts(1, shareePasswords);

        // make u1 friend all users
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), userContexts);

        // upload a file to u1's space
        String subdirName = "subdir";
        String destinationDirName = "destdir";
        u1.getUserRoot().get().mkdir(subdirName, u1.network, false, crypto).get();
        u1.getUserRoot().get().mkdir(destinationDirName, u1.network, false, crypto).get();

        Path dirPath = Paths.get(u1.username, subdirName);

        shareFunction.apply(u1, userContexts, dirPath);

        FileWrapper theDir = u1.getByPath(dirPath).get().get();
        AbsoluteCapability cap = theDir.getPointer().capability;
        Set<String> sharedWriteAccessWithBefore = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("directory shared", ! sharedWriteAccessWithBefore.isEmpty());

        //copy file
        Path destDirPath = Paths.get(u1.username, destinationDirName);
        FileWrapper destDir = u1.getByPath(destDirPath).get().get();
        theDir.copyTo(destDir, u1).join();

        //old copy should retain sharedWith entries
        Set<String> sharedWriteAccessWithOriginal = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("directory shared", ! sharedWriteAccessWithOriginal.isEmpty());

        dirPath = Paths.get(u1.username, destinationDirName, subdirName);
        theDir = u1.getByPath(dirPath).get().get();
        cap = theDir.getPointer().capability;

        Set<String> sharedWriteAccessWithNewCopy = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("directory shared", sharedWriteAccessWithNewCopy.isEmpty());
    }

    @Test
    public void moveToFileSharedWith()
            throws Exception {
        //read access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> readAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareReadAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));

        moveToFileSharedWith(readAccessSharingFunction, SharedWithCache.Access.READ);
        //write access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> writeAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareWriteAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));
        moveToFileSharedWith(writeAccessSharingFunction, SharedWithCache.Access.WRITE);
    }

    private void moveToFileSharedWith(TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> shareFunction,
            SharedWithCache.Access sharedWithAccess)
            throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        List<String> shareePasswords = IntStream.range(0, 1)
                .mapToObj(i -> PeergosNetworkUtils.generatePassword())
                .collect(Collectors.toList());
        List<UserContext> userContexts = getUserContexts(1, shareePasswords);
        // make u1 friend all users
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), userContexts);

        // upload a file to u1's space
        String filename = "somefile.txt";
        byte[] data1 = "Hello Peergos friend!".getBytes();
        AsyncReader file1Reader = new AsyncReader.ArrayBacked(data1);
        String subdirName = "subdir";
        String destinationSubdirName = "destdir";
        u1.getUserRoot().get().mkdir(subdirName, u1.network, false, crypto).get();
        u1.getUserRoot().get().mkdir(destinationSubdirName, u1.network, false, crypto).get();
        Path subdirPath = Paths.get(u1.username, subdirName);
        FileWrapper subdir = u1.getByPath(subdirPath).get().get();
        FileWrapper uploaded = subdir.uploadOrOverwriteFile(filename, file1Reader, data1.length,
                u1.network, u1.crypto, l -> {}, crypto.random.randomBytes(32)).get();

        Path filePath = Paths.get(u1.username, subdirName, filename);
        shareFunction.apply(u1, userContexts, filePath);

        FileWrapper theFile = u1.getByPath(filePath).get().get();
        Path parentPath = Paths.get(u1.username, subdirName);
        FileWrapper theParent = u1.getByPath(parentPath).get().get();
        AbsoluteCapability cap = theFile.getPointer().capability;
        Set<String> sharedWriteAccessWithBefore = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("file shared", ! sharedWriteAccessWithBefore.isEmpty());

        //move file
        Path destSubdirPath = Paths.get(u1.username, destinationSubdirName);
        FileWrapper destSubdir = u1.getByPath(destSubdirPath).get().get();

        theFile.moveTo(destSubdir, theParent, u1).join();

        //old copy sharedWith entries should be removed
        Set<String> sharedWriteAccessWithOriginal = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("file shared", sharedWriteAccessWithOriginal.isEmpty());

        filePath = Paths.get(u1.username, destinationSubdirName, filename);
        theFile = u1.getByPath(filePath).get().get();
        cap = theFile.getPointer().capability;

        //new copy sharedWith entry should also be empty
        Set<String> sharedWriteAccessWithNewCopy = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("file shared", sharedWriteAccessWithNewCopy.isEmpty());
    }

    @Test
    public void moveToDirectorySharedWith()
            throws Exception {
        //read access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> readAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareReadAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));

        moveToDirectorySharedWith(readAccessSharingFunction, SharedWithCache.Access.READ);
        //write access
        TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> writeAccessSharingFunction =
                (u1, u2List, filePath) ->
                        u1.shareWriteAccessWith(filePath, u2List.stream().map(u -> u.username).collect(Collectors.toSet()));
        moveToDirectorySharedWith(writeAccessSharingFunction, SharedWithCache.Access.WRITE);
    }

    private void moveToDirectorySharedWith(TriFunction<UserContext, List<UserContext>, Path, CompletableFuture<Boolean>> shareFunction,
                                      SharedWithCache.Access sharedWithAccess)
            throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(random(), "a", network.clear(), crypto);

        // send follow requests from each other user to "a"
        List<String> shareePasswords = IntStream.range(0, 1)
                .mapToObj(i -> PeergosNetworkUtils.generatePassword())
                .collect(Collectors.toList());
        List<UserContext> userContexts = getUserContexts(1, shareePasswords);

        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), userContexts);

        String subdirName = "subdir";
        String destinationSubdirName = "destdir";
        u1.getUserRoot().get().mkdir(subdirName, u1.network, false, crypto).get();
        u1.getUserRoot().get().mkdir(destinationSubdirName, u1.network, false, crypto).get();

        Path dirPath = Paths.get(u1.username, subdirName);
        shareFunction.apply(u1, userContexts, dirPath);

        FileWrapper theDir = u1.getByPath(dirPath).get().get();
        Path parentPath = Paths.get(u1.username);
        FileWrapper theParent = u1.getByPath(parentPath).get().get();
        AbsoluteCapability cap = theDir.getPointer().capability;
        Set<String> sharedWriteAccessWithBefore = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("directory shared", ! sharedWriteAccessWithBefore.isEmpty());

        //move directory
        Path destSubdirPath = Paths.get(u1.username, destinationSubdirName);
        FileWrapper destSubdir = u1.getByPath(destSubdirPath).get().get();

        theDir.moveTo(destSubdir, theParent, u1);

        //old copy sharedWith entries should be removed
        Set<String> sharedWriteAccessWithOriginal = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("directory shared", sharedWriteAccessWithOriginal.isEmpty());

        dirPath = Paths.get(u1.username, destinationSubdirName, subdirName);
        theDir = u1.getByPath(dirPath).get().get();
        cap = theDir.getPointer().capability;

        //new copy sharedWith entry should also be empty
        Set<String> sharedWriteAccessWithNewCopy = u1.sharedWithCache.getSharedWith(sharedWithAccess, cap);
        Assert.assertTrue("directory shared", sharedWriteAccessWithNewCopy.isEmpty());
    }

    @Test
    public void cleanRenamedFilesReadAccess() throws Exception {
        String username = random();
        String password = random();
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);

        List<String> shareePasswords = IntStream.range(0, userCount)
                .mapToObj(i -> PeergosNetworkUtils.generatePassword())
                .collect(Collectors.toList());
        // make u1 friend others
        List<UserContext> friends = getUserContexts(userCount, shareePasswords);

        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(u1), friends);

        // upload a file to u1's space
        FileWrapper u1Root = u1.getUserRoot().get();
        String filename = "somefile.txt";
        File f = File.createTempFile("peergos", "");
        byte[] originalFileContents = "Hello Peergos friend!".getBytes();
        Files.write(f.toPath(), originalFileContents);
        ResetableFileInputStream resetableFileInputStream = new ResetableFileInputStream(f);
        FileWrapper uploaded = u1Root.uploadOrOverwriteFile(filename, resetableFileInputStream, f.length(),
                u1.network, u1.crypto, l -> {}, crypto.random.randomBytes(32)).get();

        // share the file from "a" to each of the others
        String originalPath = u1.username + "/" + filename;
        FileWrapper u1File = u1.getByPath(originalPath).get().get();
        u1.shareReadAccessWith(Paths.get(u1.username, filename), friends.stream().map(u -> u.username).collect(Collectors.toSet()));

        // check other users can read the file
        for (UserContext friend : friends) {
            Optional<FileWrapper> sharedFile = friend.getByPath(u1.username + "/" + filename).get();
            Assert.assertTrue("shared file present", sharedFile.isPresent());

            AsyncReader inputStream = sharedFile.get().getInputStream(friend.network,
                    friend.crypto, l -> {}).get();

            byte[] fileContents = Serialize.readFully(inputStream, sharedFile.get().getFileProperties().size).get();
            Assert.assertTrue("shared file contents correct", Arrays.equals(originalFileContents, fileContents));
        }

        UserContext userToUnshareWith = friends.stream().findFirst().get();
        String friendsPathToFile = u1.username + "/" + filename;
        Optional<FileWrapper> priorUnsharedView = userToUnshareWith.getByPath(friendsPathToFile).get();
        AbsoluteCapability priorPointer = priorUnsharedView.get().getPointer().capability;
        CommittedWriterData cwd = network.synchronizer.getValue(priorPointer.owner, priorPointer.writer).join().get(priorPointer.writer);
        CryptreeNode priorFileAccess = network.getMetadata(cwd.props, priorPointer).get().get();
        SymmetricKey priorMetaKey = priorFileAccess.getParentKey(priorPointer.rBaseKey);

        // unshare with a single user
        u1.unShareReadAccess(Paths.get(u1.username, filename), userToUnshareWith.username).join();

        String newname = "newname.txt";
        FileWrapper updatedParent = u1.getByPath(originalPath).get().get()
                .rename(newname, u1.getUserRoot().get(), u1).get();
        Path newPath = Paths.get(u1.username, newname);
        AbsoluteCapability newCap = u1.getByPath(newPath).join().get().getPointer().capability;

        // check still logged in user can't read the new name
        Optional<FileWrapper> unsharedView = userToUnshareWith.getByPath(friendsPathToFile).get();
        String friendsNewPathToFile = u1.username + "/" + newname;
        Optional<FileWrapper> unsharedView2 = userToUnshareWith.getByPath(friendsNewPathToFile).get();
        CommittedWriterData cwd2 = network.synchronizer.getValue(priorPointer.owner, priorPointer.writer).join().get(priorPointer.writer);
        CryptreeNode fileAccess = network.getMetadata(cwd2.props, priorPointer.withMapKey(newCap.getMapKey())).get().get();
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
                        return ensureSignedUp(e.username, shareePasswords.get(friends.indexOf(e)), network, crypto);
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
                u1New.network, crypto, l -> {}, null).get();
        AsyncReader extendedContents = u1New.getByPath(u1.username + "/" + newname).get().get()
                .getInputStream(u1New.network, crypto, l -> {}).get();
        byte[] newFileContents = Serialize.readFully(extendedContents, originalFileContents.length + suffix.length).get();

        Assert.assertTrue(Arrays.equals(newFileContents, ArrayOps.concat(originalFileContents, suffix)));
    }

    private String random() {
        return ArrayOps.bytesToHex(crypto.random.randomBytes(15));
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

        Set<FileWrapper> children = u2ToU1.get().getChildren(crypto.hasher, u2.network).get();

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
    public void verifyFriend() {
        String username1 = random();
        String password1 = random();
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp(username1, password1, network, crypto);
        String username2 = random();
        String password2 = random();
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp(username2, password2, network, crypto);
        u2.sendFollowRequest(u1.username, SymmetricKey.random()).join();
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().join();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true).join();
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().join();

        // verify a friend and persist the result
        Pair<List<PublicKeyHash>, FingerPrint> u2FingerPrint = u2.generateFingerPrint(username1).join();
        Pair<List<PublicKeyHash>, FingerPrint> u1FingerPrint = u1.generateFingerPrint(username2).join();

        Assert.assertTrue("Verify fingerprint", u1FingerPrint.right.matches(u2FingerPrint.right));

        u1.addFriendAnnotation(new FriendAnnotation(username2, true, u1FingerPrint.left)).join();

        SocialState u1Social = PeergosNetworkUtils.ensureSignedUp(username1, password1, network.clear(), crypto)
                .getSocialState().join();
        FriendAnnotation annotation = u1Social.friendAnnotations.get(username2);
        Assert.assertTrue("Annotation persisted", annotation != null && annotation.isVerified());
    }

    @Test
    public void followPeergos() throws Exception {
        UserContext u1 = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        UserContext u2 = PeergosNetworkUtils.ensureSignedUp("w", "somepassword", network, crypto);

        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
    }

    @Test
    public void sendFeedbackToPeergos() throws Exception {
        UserContext peergos = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        UserContext newUser = PeergosNetworkUtils.ensureSignedUp("new-user", "newUserPassword", network, crypto);

        // Check that new user can send feedback to the user peergos.
        String feedback = "Here's some constructive feedback!";
        CompletableFuture<Boolean> testFeedbackSubmission = newUser.submitFeedback(feedback);
        assertTrue("Feedback submission was successful!", testFeedbackSubmission.get() == true);

        // Can peergos read the feedback file?
        List<FollowRequestWithCipherText> peergosRequests = peergos.processFollowRequests().get();
        for (FollowRequestWithCipherText request : peergosRequests) {
            peergos.sendReplyFollowRequest(request, true, true).get();
        }

        Optional<FileWrapper> newUserToPeergos = peergos.getByPath("/" + newUser.username + "/feedback").get();
        Set<FileWrapper> feedbackDirectoryContents = newUserToPeergos.get().getChildren(crypto.hasher, newUser.network).get();
        assertTrue("Feedback directory is non-empty", !feedbackDirectoryContents.isEmpty());

        for (FileWrapper feedbackFile : feedbackDirectoryContents) {
            assertTrue("Feedback file is readable", feedbackFile.isReadable());

            AsyncReader inputStream = feedbackFile
                        .getInputStream(peergos.network, peergos.crypto, l -> {})
                        .get();

            byte[] fileContents = Serialize.readFully(inputStream, feedbackFile.getFileProperties().size).get();
            String reportedFeedback = new String(fileContents);
            assertTrue("Feedback file contents correct", Objects.equals(feedback, reportedFeedback));
        }
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
        Assert.assertTrue("u1 no longer following u2", !newU1Following.contains(u2.username));

        Optional<FileWrapper> u2Tou1 = u1.getByPath("/" + u2.username).get();
        assertTrue("u1 can no longer see u2's root", !u2Tou1.isPresent());

        Optional<FileWrapper> u1Tou2 = u2.getByPath("/" + u1.username).get();
        assertTrue("u2 can still see u1's root", u1Tou2.isPresent());
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
        Assert.assertTrue("u1 no longer has u2 as follower", !newU1Followers.contains(u2.username));
        
        Set<String> u2Following = u2.getFollowing().get();
        Assert.assertTrue("u2 is no longer following u1", !u2Following.contains(u1.username));

        Optional<FileWrapper> u2Tou1 = u1.getByPath("/" + u2.username).get();
        assertTrue("u1 can still see u2's root", u2Tou1.isPresent());

        Optional<FileWrapper> u1Tou2 = u2.getByPath("/" + u1.username).get();
        assertTrue("u2 can no longer see u1's root", !u1Tou2.isPresent());
    }
}
