package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;

import java.time.*;
import java.util.*;
import java.util.stream.*;

public class BlockSizeTests {
    private static Crypto crypto = Main.initCrypto();

    @Test
    public void largeDirectoryCryptreeNode() {
        SymmetricKey rBase = SymmetricKey.random();
        SymmetricKey wBase = SymmetricKey.random();
        SymmetricKey parent = SymmetricKey.random();
        SymmetricKey parentParent = SymmetricKey.random();
        Optional<BatId> mirrorBatId = Optional.of(BatId.sha256(Bat.random(crypto.random), crypto.hasher).join());
        FileProperties props = new FileProperties("a-directory", true, false, "", 0, 0,
                LocalDateTime.now(), LocalDateTime.now(), false, Optional.empty(), Optional.empty());
        SigningPrivateKeyAndPublicHash signingPair = ChampTests.createUser(new RAMStorage(crypto.hasher), crypto);

        Optional<RelativeCapability> parentCap = Optional.of(new RelativeCapability(Optional.empty(), crypto.random.randomBytes(32), Optional.of(Bat.random(crypto.random)), parentParent, Optional.empty()));
        RelativeCapability nextChunk = new RelativeCapability(Optional.empty(), crypto.random.randomBytes(32), Optional.of(Bat.random(crypto.random)), parentParent, Optional.empty());

        String nameBase = IntStream.range(0, 252).mapToObj(i -> "A").collect(Collectors.joining());
        List<NamedRelativeCapability> children = IntStream.range(0, 500)
                .mapToObj(i -> new NamedRelativeCapability(nameBase + String.format("%03d", i), nextChunk))
                .collect(Collectors.toList());
        CryptreeNode.ChildrenLinks childrenLinks = new CryptreeNode.ChildrenLinks(children);
        CryptreeNode.DirAndChildren dir = CryptreeNode.createDir(MaybeMultihash.empty(), rBase,
                wBase, Optional.of(signingPair), props, parentCap, parent, nextChunk, childrenLinks, Optional.of(Bat.random(crypto.random)), mirrorBatId, crypto.random, crypto.hasher).join();

        byte[] raw = dir.dir.serialize();
        Assert.assertTrue(raw.length < Fragment.MAX_LENGTH);
    }
}
