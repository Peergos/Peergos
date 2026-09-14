package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.BlockWriteAuth;
import peergos.shared.storage.auth.BatId;

import java.util.*;

/** The block sizes in a write auth are not covered by its signature, so they are attacker controlled
 *  even for a caller with a valid signing key. They are summed for the quota check and used as the
 *  presigned upload's content length, so they have to be validated on the way in.
 */
public class BlockWriteAuthTests {

    private static Cid hash(int seed) {
        byte[] digest = new byte[32];
        Arrays.fill(digest, (byte) seed);
        return Cid.buildCidV1(Cid.Codec.Raw, Multihash.Type.sha2_256, digest);
    }

    /** The wire form of a write auth, built directly so it can carry sizes the constructor rejects. */
    private static CborObject.CborMap cborWithSizes(List<Long> sizes) {
        List<Cborable> hashes = new ArrayList<>();
        List<Cborable> lengths = new ArrayList<>();
        List<Cborable> bats = new ArrayList<>();
        for (int i = 0; i < sizes.size(); i++) {
            hashes.add(new CborObject.CborMerkleLink(hash(i)));
            lengths.add(new CborObject.CborLong(sizes.get(i)));
            bats.add(new CborObject.CborList(Collections.<BatId>emptyList()));
        }
        SortedMap<String, Cborable> props = new TreeMap<>();
        props.put("h", new CborObject.CborList(hashes));
        props.put("l", new CborObject.CborList(lengths));
        props.put("b", new CborObject.CborList(bats));
        props.put("s", new CborObject.CborByteArray(new byte[64]));
        return CborObject.CborMap.build(props);
    }

    @Test
    public void aNegativeBlockSizeIsRejected() {
        try {
            new BlockWriteAuth(List.of(hash(1)), List.of(-1L), List.of(List.of()), new byte[64]);
            Assert.fail("a negative block size was accepted");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("Invalid block size"));
        }
    }

    /** A negative size would otherwise reach the quota check, which sums the sizes and casts to int,
     *  so one block declaring a large negative size hides the cost of the rest of the batch.
     */
    @Test
    public void aNegativeSizeIsRejectedWhenParsedFromTheWire() {
        byte[] wire = cborWithSizes(List.of(1024L, -4096L)).serialize();
        try {
            BlockWriteAuth.fromCbor(CborObject.fromByteArray(wire));
            Assert.fail("a negative block size was accepted off the wire");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("Invalid block size"));
        }
    }

    @Test
    public void aSizeAboveTheIntRangeIsRejected() {
        try {
            new BlockWriteAuth(List.of(hash(1)), List.of(Integer.MAX_VALUE + 1L), List.of(List.of()), new byte[64]);
            Assert.fail("a block size beyond the int range was accepted");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("Invalid block size"));
        }
    }

    @Test
    public void validSizesStillRoundTrip() {
        BlockWriteAuth auth = new BlockWriteAuth(List.of(hash(1), hash(2)), List.of(0L, 4096L),
                List.of(List.of(), List.of()), new byte[64]);
        BlockWriteAuth parsed = BlockWriteAuth.fromCbor(CborObject.fromByteArray(auth.serialize()));
        Assert.assertEquals(auth.sizes, parsed.sizes);
        Assert.assertEquals(auth.hashes, parsed.hashes);
    }
}
