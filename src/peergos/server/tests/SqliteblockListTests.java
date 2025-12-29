package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.storage.SqliteBlockList;
import peergos.server.storage.UserBlockVersion;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

public class SqliteblockListTests {

    @Test
    public void legacy() throws IOException {
        Path dir = Files.createTempDirectory("block-list");
        SqliteBlockList blocks = SqliteBlockList.createBlockListDb(dir.resolve("blocks.sqlite"));
        Cid h = randomCbor(new Random(42));
        String version = "version";
        blocks.addBlocks(List.of(new UserBlockVersion(null, h, version, true)));
        List<String> versions = blocks.getVersions(null, h);
        Assert.assertEquals(List.of(version), versions);
    }

    private Cid randomCbor(Random r) {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
    }
}
