package peergos.server.tests;

import org.junit.*;
import peergos.shared.cbor.*;
import peergos.shared.display.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.social.*;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.util.*;

import java.io.*;
import java.lang.management.*;
import java.time.*;
import java.util.*;

public class CborObjects {
    private final Random rnd = new Random();

    private byte[] random(int len) {
        byte[] res = new byte[len];
        rnd.nextBytes(res);
        return res;
    }

    @Test
    public void dosCborObject() throws Throwable {
        // make a header for a byte[] that is 2^50 long
        byte[] raw = ArrayOps.hexToBytes("5b0004000000000000");
        try {
            CborObject.fromByteArray(raw);
            Assert.fail("Should have failed!");
        } catch (RuntimeException e) {}
    }

    private static byte[] nested(String levelHex, int levels, String leafHex) {
        byte[] level = ArrayOps.hexToBytes(levelHex), leaf = ArrayOps.hexToBytes(leafHex);
        byte[] res = new byte[level.length * levels + leaf.length];
        for (int i=0; i < levels; i++)
            System.arraycopy(level, 0, res, i * level.length, level.length);
        System.arraycopy(leaf, 0, res, level.length * levels, leaf.length);
        return res;
    }

    private static void assertRejected(byte[] cbor) {
        try {
            CborObject.fromByteArray(cbor);
            Assert.fail("Should have failed!");
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void deepNesting() {
        // a 64 KiB input of arrays each declaring 65,535 items
        assertRejected(Arrays.copyOf(nested("99ffff", 4_000, ""), 64 * 1024));
        // merkle link tags
        assertRejected(nested("d82a", 30_000, ""));
        // {"": {"": ... 0}}
        byte[] deepest = nested("a160", CborObject.MAX_DEPTH, "00");
        Assert.assertArrayEquals(deepest, CborObject.fromByteArray(deepest).toByteArray());
        assertRejected(nested("a160", CborObject.MAX_DEPTH + 1, "00"));
    }

    @Test
    public void declaredArrayLengthsAreNotPreallocated() {
        // a 1 MiB input of arrays nested to the limit, each declaring a million items, then an invalid item
        byte[] cbor = new byte[1024 * 1024];
        Arrays.fill(cbor, (byte) 0xff);
        byte[] arrays = nested("9a000fffff", CborObject.MAX_DEPTH, "");
        System.arraycopy(arrays, 0, cbor, 0, arrays.length);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        assertRejected(cbor);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        Assert.assertTrue("Allocated " + allocated + " bytes", allocated < cbor.length);
    }

    @Test
    public void editedSocialPost() {
        // every edit nests the previous version 3 levels deeper
        SocialPost post = SocialPost.createInitialPost("alice", List.of(new Text("v0")), SocialPost.Resharing.Friends);
        for (int i=1; i <= 16; i++)
            post = post.edit(List.of(new Text("v" + i)), LocalDateTime.now());
        byte[] raw = post.serialize();
        Assert.assertArrayEquals(raw, SocialPost.fromCbor(CborObject.fromByteArray(raw)).serialize());
    }

    @Test
    public void cborNull() {
        CborObject.CborNull cbor = new CborObject.CborNull();
        compatibleAndIdempotentSerialization(cbor);
    }

    @Test
    public void cborString() {
        String value = "G'day mate!";
        CborObject.CborString cbor = new CborObject.CborString(value);
        compatibleAndIdempotentSerialization(cbor);
    }

    @Test
    public void cborByteArray() {
        byte[] value = random(32);
        CborObject.CborByteArray cbor = new CborObject.CborByteArray(value);
        compatibleAndIdempotentSerialization(cbor);
    }

    @Test
    public void cborBoolean() {
        compatibleAndIdempotentSerialization(new CborObject.CborBoolean(true));
        compatibleAndIdempotentSerialization(new CborObject.CborBoolean(false));
    }

    @Test
    public void cborLongs() {
        cborLong(rnd.nextLong());
        cborLong(Long.MAX_VALUE);
        cborLong(Long.MIN_VALUE);
        cborLong(Integer.MAX_VALUE);
        cborLong(Integer.MIN_VALUE);
        cborLong(0);
        cborLong(100);
        cborLong(-100);
    }

    private void cborLong(long value) {
        CborObject.CborLong cbor = new CborObject.CborLong(value);
        compatibleAndIdempotentSerialization(cbor);
    }

    @Test
    public void cborMerkleLink() {
        Multihash hash = Multihash.fromBase58("QmPZ9gcCEpqKTo6aq61g2nXGUhM4iCL3ewB6LDXZCtioEB");
        CborObject.CborMerkleLink link = new CborObject.CborMerkleLink(hash);
        compatibleAndIdempotentSerialization(link);
    }

    @Test
    public void cborMap() {
        SortedMap<String, Cborable> map = new TreeMap<>();
        map.put("KEY 1", new CborObject.CborString("A value"));
        map.put("KEY 2", new CborObject.CborByteArray("Another value".getBytes()));
        map.put("KEY 3", new CborObject.CborNull());
        map.put("KEY 4", new CborObject.CborBoolean(true));
        Multihash hash = Multihash.fromBase58("QmPZ9gcCEpqKTo6aq61g2nXGUhM4iCL3ewB6LDXZCtioEB");
        CborObject.CborMerkleLink link = new CborObject.CborMerkleLink(hash);
        map.put("Key 5", link);
        List<CborObject> list = new ArrayList<>();
        list.add(new CborObject.CborBoolean(true));
        list.add(new CborObject.CborNull());
        list.add(new CborObject.CborLong(256));
        map.put("KEY 6", new CborObject.CborList(list));
        CborObject.CborMap cborMap = CborObject.CborMap.build(map);
        compatibleAndIdempotentSerialization(cborMap);
    }

    @Test
    public void cborList() {
        List<CborObject> list = new ArrayList<>();
        list.add(new CborObject.CborString("A value"));
        list.add(new CborObject.CborByteArray("A value".getBytes()));
        list.add(new CborObject.CborNull());
        list.add(new CborObject.CborBoolean(true));
        CborObject.CborList cborList = new CborObject.CborList(list);
        compatibleAndIdempotentSerialization(cborList);
    }

    @Test
    public void parseStreamAcrossChunkBoundary() throws Exception {
        // these sizes make the second object straddle a Chunk.LEGACY_SIZE read boundary, which used to
        // result in the third object being dropped
        List<CborObject> objects = new ArrayList<>();
        objects.add(new CborObject.CborByteArray(random(2_000_000)));
        objects.add(new CborObject.CborByteArray(random(4_000_000)));
        objects.add(new CborObject.CborByteArray(random(500_000)));

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        for (CborObject o : objects)
            bout.write(o.toByteArray());
        byte[] serialized = bout.toByteArray();

        List<CborObject> parsed = new ArrayList<>();
        new AsyncReader.ArrayBacked(serialized).parseStream(c -> (CborObject) c, parsed::add, serialized.length).join();

        Assert.assertEquals("All objects parsed", objects.size(), parsed.size());
        Assert.assertEquals(objects, parsed);
    }

    public void compatibleAndIdempotentSerialization(CborObject value) {
        byte[] raw = value.toByteArray();
        CborObject deserialized = CborObject.fromByteArray(raw);

        boolean equals = deserialized.equals(value);
        Assert.assertTrue("Equal objects", equals);
        byte[] raw2 = deserialized.toByteArray();
        boolean sameRaw = Arrays.equals(raw, raw2);
        Assert.assertTrue("Idempotent serialization", sameRaw);
    }
}
