package peergos.server.tests;
import org.junit.*;
import static org.junit.Assert.*;
import peergos.server.SpaceCheckingKeyFilter;
import peergos.shared.Crypto;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.crypto.hash.Sha256;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.merklebtree.MaybeMultihash;

import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import static peergos.server.SpaceCheckingKeyFilter.*;

public class SpaceCheckingKeyFilterTests {
    private static Random RANDOM = new Random(666);
    private final Crypto crypto = Crypto.initJava();

    private static byte[] random() {
        byte[] bytes = new byte[Multihash.Type.sha2_256.length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
    @Test
    public void serdeTest() {

        ConcurrentHashMap<PublicKeyHash, SpaceCheckingKeyFilter.Stat> currentView = new ConcurrentHashMap<>();
        Multihash hash = new Multihash(Multihash.Type.sha2_256, random());
        currentView.put(new PublicKeyHash(hash), new Stat("test1", MaybeMultihash.empty(), 10, new HashSet<>()));

        ConcurrentHashMap<String, SpaceCheckingKeyFilter.Usage> usage = new ConcurrentHashMap<>();
        usage.put("test2", new Usage(RANDOM.nextInt()));

        State state = new State(currentView, usage);

        CborObject cborObject = state.toCbor();
        byte[] serialized = cborObject.serialize();

        State deserialized = fromCbor(CborObject.fromByteArray(serialized));
        //check that deserialize(serialize(object)) == object
        Assert.assertEquals(deserialized, state);
    }
}
