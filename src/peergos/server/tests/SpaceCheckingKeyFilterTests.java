package peergos.server.tests;
import org.junit.*;
import peergos.server.*;
import peergos.server.space.*;
import peergos.shared.Crypto;
import peergos.shared.MaybeMultihash;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;

import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;


public class SpaceCheckingKeyFilterTests {
    private static Random RANDOM = new Random(666);
    private final Crypto crypto = Main.initCrypto();

    private static byte[] random() {
        byte[] bytes = new byte[Multihash.Type.sha2_256.length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    @Test
    public void serdeTest() {

        ConcurrentHashMap<PublicKeyHash, WriterUsage> currentView = new ConcurrentHashMap<>();
        Cid hash = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, random());
        currentView.put(new PublicKeyHash(hash), new WriterUsage("test1", MaybeMultihash.empty(), 10, new HashSet<>()));

        ConcurrentHashMap<String, UserUsage> usage = new ConcurrentHashMap<>();
        usage.put("test2", new UserUsage(RANDOM.nextInt()));

        RamUsageStore.State state = new RamUsageStore.State(currentView, usage);

        CborObject cborObject = state.toCbor();
        byte[] serialized = cborObject.serialize();

        RamUsageStore.State deserialized = RamUsageStore.State.fromCbor(CborObject.fromByteArray(serialized));
        //check that deserialize(serialize(object)) == object
        Assert.assertEquals(deserialized, state);
    }
}
