package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.storage.NewBlocksProcessor;
import peergos.server.storage.RAMStorage;
import peergos.server.storage.auth.Want;
import peergos.server.util.Threads;
import peergos.shared.Crypto;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.TransactionId;
import peergos.shared.storage.auth.BatWithId;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public class MirrorTests {
    private static Random r = new Random(42);

    private static byte[] randomBlock() {
        byte[] bytes = new byte[32];
        r.nextBytes(bytes);
        return bytes;
    }

    @Test
    public void test() {
        Crypto crypto = Main.initCrypto();
        // Build a 10 deep binary tree
        TestMirrorStorage s = new TestMirrorStorage(crypto.hasher);

        int depth = 10;
        int branches = 2;
        List<byte[]> leaves = IntStream.range(0, (int) Math.pow(2, depth - 1)).mapToObj(i -> randomBlock()).toList();
        TransactionId tid = s.startTransaction(null).join();
        List<Cid> leafCids = leaves.stream()
                .map(b -> s.putRaw(null, null, null, b, tid, x -> {}).join())
                .toList();

        List<Cid> level = leafCids;
        while (level.size() > 1) {
            List<Cid> nextLevel = new ArrayList<>();
            for (int i=0; i < level.size(); i+= branches)
                nextLevel.add(s.put(null, null, null, new CborObject.CborList(level.subList(i, Math.min(level.size(), i + branches))
                        .stream().map(CborObject.CborMerkleLink::new).toList()).toByteArray(), tid).join());
            level = nextLevel;
        }

        Cid root = level.get(0);
        AtomicLong count = new AtomicLong(0);
        NewBlocksProcessor p = (w, bs) -> count.addAndGet(bs.size());
        s.mirror("a", null, null, Collections.emptyList(), Optional.empty(),
                Optional.of(root), Optional.empty(), null, p, tid, crypto.hasher).join();
        Assert.assertEquals((1 << depth) - 1, count.get());
        // Ths is necessary for mirror to be fast in a p2p setting when getLinks retrieves the blocks
        Assert.assertEquals(depth, s.highLatencyCalls.get());
    }

    public static class TestMirrorStorage extends RAMStorage {
        public final AtomicLong highLatencyCalls = new AtomicLong(0);

        public TestMirrorStorage(Hasher hasher) {
            super(hasher);
        }

        @Override
        public List<List<Cid>> bulkGetLinks(List<Multihash> peerIds, List<Want> wants) {
            highLatencyCalls.incrementAndGet();
            return super.bulkGetLinks(peerIds, wants);
        }
    }
}
