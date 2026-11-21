package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.sync.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** A write to part of a file must leave the file's stored tree hash describing what the file now holds, as a write of
 *  the whole file does: sync, uploads and the next partial write all trust it to tell them what they can skip.
 */
public class PartialWriteHashTests {
    private static final Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private static final int CHUNK = FileProperties.chunkSizeForNewFiles();
    private final Random random = new Random();

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private NetworkAccess network() {
        return NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
    }

    private UserContext user() {
        UserContext user = PeergosNetworkUtils.ensureSignedUp(PeergosNetworkUtils.generateUsername(random), "password",
                network(), crypto);
        user.getUserRoot().join().mkdir("dir", user.network, false, user.mirrorBatId(), crypto).join();
        return user;
    }

    private static FileWrapper get(UserContext user, String name) {
        return user.getByPath(PathUtil.get(user.username, "dir", name)).join().get();
    }

    private byte[] random(int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        return data;
    }

    private static byte[] upload(UserContext user, String name, byte[] data) {
        user.getByPath(PathUtil.get(user.username, "dir")).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, user.network, crypto, () -> false, x -> {}).join();
        return data;
    }

    private static byte[] read(UserContext user, String name) {
        FileWrapper file = get(user, name);
        return Serialize.readFully(file.getInputStream(user.network, crypto, x -> {}).join(), file.getSize()).join();
    }

    private static HashTree treeOf(byte[] data) {
        return HashTree.build(AsyncReader.build(data), 0, data.length, CHUNK, crypto.hasher).join();
    }

    private static byte[] with(byte[] data, int at, byte[] section) {
        byte[] res = Arrays.copyOf(data, Math.max(data.length, at + section.length));
        System.arraycopy(section, 0, res, at, section.length);
        return res;
    }

    private static void writeSection(FileWrapper file, UserContext user, int at, byte[] section) {
        file.overwriteSectionJS(AsyncReader.build(section), 0, at, 0, at + section.length, Optional.empty(),
                user.network, crypto, x -> {}).join();
    }

    /** One byte changed in the second chunk */
    private static byte[] laterChunkEdited(byte[] original) {
        return with(original, CHUNK + 10, new byte[]{(byte) ~original[CHUNK + 10]});
    }

    /** The file holds contents, and stores the hash a write of all of them would */
    private static void assertHolds(String message, UserContext user, String name, byte[] contents) {
        Assert.assertArrayEquals(message, contents, read(user, name));
        Assert.assertEquals(message, Optional.of(treeOf(contents).branch(0)), get(user, name).getFileProperties().treeHash);
    }

    /** The file holds contents, and stores either no hash or theirs */
    private static void assertHoldsWithNoOldHash(UserContext user, String name, byte[] contents) {
        Assert.assertArrayEquals(contents, read(user, name));
        Optional<HashBranch> stored = get(user, name).getFileProperties().treeHash;
        Assert.assertTrue("stored " + stored, stored.isEmpty() || stored.get().equals(treeOf(contents).branch(0)));
    }

    @Test
    public void overwriteChangedChunksStoresTheHashOfAWholeWrite() {
        UserContext user = user();
        byte[] original = upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        byte[] edited = laterChunkEdited(original);

        get(user, "a").overwriteChangedChunks(AsyncReader.build(edited), edited.length, user.network, crypto, x -> {}).join();

        assertHolds("an edit of a later chunk", user, "a", edited);
        upload(user, "whole", edited);
        Assert.assertEquals(get(user, "whole").getFileProperties().treeHash, get(user, "a").getFileProperties().treeHash);
    }

    @Test
    public void overwriteChangedChunksToANewSize() {
        UserContext user = user();
        byte[] original = random(2 * CHUNK + CHUNK / 2);
        for (int size : new int[]{original.length + 1000, 3 * CHUNK + 7, CHUNK + CHUNK / 3, 2 * CHUNK, CHUNK, CHUNK / 2, 0}) {
            String name = "f" + size;
            upload(user, name, original);
            // the start is unchanged, so only the end is written
            byte[] replacement = Arrays.copyOf(original, size);
            if (size > CHUNK + 10)
                replacement[CHUNK + 10] ^= 1;

            get(user, name).overwriteChangedChunks(AsyncReader.build(replacement), size, user.network, crypto, x -> {}).join();

            assertHolds("to " + size + " bytes", user, name, replacement);
        }
    }

    @Test
    public void theStoredHashIsOfTheBytesWritten() {
        UserContext user = user();
        byte[] original = upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        byte[] edited = laterChunkEdited(original);

        // as if the source changed back between hashing it and reading it
        get(user, "a").overwriteChangedChunks(AsyncReader.build(edited), edited.length, Optional.of(treeOf(original)),
                Optional.empty(), user.network, crypto, x -> {}).join();

        assertHolds("the edit", user, "a", edited);
    }

    @Test
    public void overwriteChangedChunksWithTheOriginalBytesRestoresThem() {
        UserContext user = user();
        byte[] original = upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        byte[] edited = laterChunkEdited(original);
        get(user, "a").overwriteChangedChunks(AsyncReader.build(edited), edited.length, user.network, crypto, x -> {}).join();

        get(user, "a").overwriteChangedChunks(AsyncReader.build(original), original.length, user.network, crypto, x -> {}).join();

        assertHolds("the original", user, "a", original);
    }

    @Test
    public void uploadingTheOriginalBytesWithTheirHashRestoresThem() {
        UserContext user = user();
        byte[] original = upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        byte[] edited = laterChunkEdited(original);
        get(user, "a").overwriteChangedChunks(AsyncReader.build(edited), edited.length, user.network, crypto, x -> {}).join();

        FileWrapper.FileUploadProperties file = new FileWrapper.FileUploadProperties("a", () -> AsyncReader.build(original),
                0, original.length, Optional.empty(), Optional.of(treeOf(original)), false, true, x -> {});
        user.getUserRoot().join().uploadSubtree(Stream.of(new FileWrapper.FolderUploadProperties(List.of("dir"), List.of(file))),
                user.mirrorBatId(), user.network, crypto, user.getTransactionService(),
                x -> Futures.of(true), x -> Futures.of(true), () -> true).join();

        assertHolds("the original", user, "a", original);
    }

    @Test
    public void anEditOfALaterChunkReachesEverySyncClient() throws Exception {
        UserContext user = user();
        Path remoteRoot = PathUtil.get(user.username, "dir");
        String link = user.createSecretLink(remoteRoot.toString(), true, Optional.empty(), Optional.<Integer>empty(), "", false)
                .join().toLinkString(user.signer.publicKeyHash);
        List<Path> bases = new ArrayList<>();
        List<LocalFileSystem> locals = new ArrayList<>();
        List<PeergosSyncFS> remotes = new ArrayList<>();
        List<SyncState> states = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            bases.add(Files.createTempDirectory("peergos-sync"));
            locals.add(new LocalFileSystem(bases.get(i), crypto.hasher));
            remotes.add(DirectorySync.buildRemote(link, user.network, crypto));
            states.add(new JdbcTreeState(":memory:"));
        }
        byte[] original = random(2 * CHUNK + CHUNK / 2);
        Files.write(bases.get(0).resolve("a"), original);
        for (int i = 0; i < 2; i++)
            sync(locals.get(i), remotes.get(i), states.get(i));
        Assert.assertArrayEquals(original, Files.readAllBytes(bases.get(1).resolve("a")));

        // a mount's save of the file
        byte[] edited = laterChunkEdited(original);
        get(user, "a").overwriteChangedChunks(AsyncReader.build(edited), edited.length, user.network, crypto, x -> {}).join();

        for (int i = 0; i < 2; i++)
            sync(locals.get(i), remotes.get(i), states.get(i));
        for (int i = 0; i < 2; i++)
            Assert.assertArrayEquals("client " + i, edited, Files.readAllBytes(bases.get(i).resolve("a")));
        assertHolds("the edit", user, "a", edited);
    }

    private static void sync(LocalFileSystem local, PeergosSyncFS remote, SyncState state) throws Exception {
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
    }

    @Test
    public void aSectionWriteStoresTheNewContentsHash() {
        UserContext user = user();
        byte[] contents = upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        // within a later chunk, then across the boundary of two
        for (int at : new int[]{CHUNK + 10, 2 * CHUNK - 500}) {
            byte[] section = random(1000);
            writeSection(get(user, "a"), user, at, section);
            contents = with(contents, at, section);
            assertHolds("a section at " + at, user, "a", contents);
        }
    }

    @Test
    public void anAppendStoresTheNewContentsHash() {
        UserContext user = user();
        byte[] contents = upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        // within the last chunk, then into a new one
        for (int length : new int[]{1000, CHUNK}) {
            byte[] tail = random(length);
            user.getByPath(PathUtil.get(user.username, "dir")).join().get()
                    .appendFileJS("a", AsyncReader.build(tail), 0, tail.length, user.network, crypto, x -> {}).join();
            contents = with(contents, contents.length, tail);
            assertHolds("an append of " + length, user, "a", contents);
        }
    }

    @Test
    public void aWriteThatLeavesAGapLeavesNoOldHash() {
        UserContext user = user();
        byte[] original = upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        byte[] section = random(1000);

        // the part chunk at the end now reads padded with zeros, which its stored hash doesn't describe
        writeSection(get(user, "a"), user, 3 * CHUNK + 10, section);

        assertHoldsWithNoOldHash(user, "a", with(original, 3 * CHUNK + 10, section));
    }

    @Test
    public void truncateStoresTheNewContentsHash() {
        UserContext user = user();
        byte[] original = random(2 * CHUNK + CHUNK / 2);
        for (int size : new int[]{CHUNK + CHUNK / 3, 2 * CHUNK, CHUNK, CHUNK / 2, 0}) {
            String name = "f" + size;
            upload(user, name, original);

            get(user, name).truncate(size, user.network, crypto).join();

            assertHolds("to " + size + " bytes", user, name, Arrays.copyOf(original, size));
        }
    }

    @Test
    public void aShorterOverwriteStoresTheNewContentsHash() {
        UserContext user = user();
        upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        byte[] replacement = random(CHUNK + CHUNK / 3);

        get(user, "a").overwriteFile(AsyncReader.build(replacement), replacement.length, user.network, crypto, x -> {}).join();

        assertHolds("the replacement", user, "a", replacement);
    }

    @Test
    public void aWriteThroughAnOutOfDateCopyStoresTheHashOfWhatTheFileHolds() {
        UserContext user = user();
        byte[] original = upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        FileWrapper stale = get(user, "a");
        byte[] changed = with(original, 10, random(1000));
        get(user, "a").overwriteChangedChunks(AsyncReader.build(changed), changed.length, user.network, crypto, x -> {}).join();

        byte[] section = random(1000);
        writeSection(stale, user, CHUNK + 10, section);

        assertHolds("both writes", user, "a", with(changed, CHUNK + 10, section));
    }

    @Test
    public void aWriteThroughACopyOfAnotherSizeLeavesNoOldHash() {
        UserContext user = user();
        byte[] original = upload(user, "a", random(2 * CHUNK + CHUNK / 2));
        FileWrapper stale = get(user, "a");
        byte[] longer = upload(user, "a", with(original, original.length, random(1000)));

        byte[] section = random(1000);
        writeSection(stale, user, CHUNK + 10, section);

        assertHoldsWithNoOldHash(user, "a", with(longer, CHUNK + 10, section));
    }
}
