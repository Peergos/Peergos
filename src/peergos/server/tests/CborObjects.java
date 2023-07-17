package peergos.server.tests;

import org.junit.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.*;

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
