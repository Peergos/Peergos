package peergos.server.tests;

import org.junit.*;
import peergos.shared.cbor.*;

import java.util.*;

public class CborObjects {
    private final Random rnd = new Random();

    private byte[] random(int len) {
        byte[] res = new byte[len];
        rnd.nextBytes(res);
        return res;
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
    public void cborMap() {
        SortedMap<CborObject, CborObject> map = new TreeMap<>();
        map.put(new CborObject.CborString("KEY 1"), new CborObject.CborString("A value"));
        map.put(new CborObject.CborString("KEY 2"), new CborObject.CborByteArray("A value".getBytes()));
        map.put(new CborObject.CborString("KEY 3"), new CborObject.CborNull());
        CborObject.CborMap cborMap = new CborObject.CborMap(map);
        compatibleAndIdempotentSerialization(cborMap);
    }

    @Test
    public void cborList() {
        List<CborObject> list = new ArrayList<>();
        list.add(new CborObject.CborString("A value"));
        list.add(new CborObject.CborByteArray("A value".getBytes()));
        list.add(new CborObject.CborNull());
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
