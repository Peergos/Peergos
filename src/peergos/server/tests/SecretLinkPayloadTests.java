package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.CipherText;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.crypto.symmetric.TweetNaClKey;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.storage.auth.Bat;
import peergos.shared.storage.auth.BatId;
import peergos.shared.user.fs.AbsoluteCapability;
import peergos.shared.user.fs.EncryptedCapability;
import peergos.shared.user.fs.WritableAbsoluteCapability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The payload inside a secret link carries one capability or several.
 *
 * The cbor type is what says which - an {@link AbsoluteCapability} is always a map, a list of them
 * is a list - so there is no version field and nothing to migrate. That only holds if a one item
 * link keeps being written as a bare map, which is what most of this pins: get it wrong and every
 * link written from now on is unreadable by clients already in the field, which is not a thing that
 * can be fixed afterwards.
 */
public class SecretLinkPayloadTests {

    private static final Crypto crypto = Main.initCrypto();

    private static PublicKeyHash randomHash(int seed) {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) seed);
        return new PublicKeyHash(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, raw));
    }

    private static AbsoluteCapability readOnlyCap(int seed) {
        byte[] mapKey = new byte[32];
        Arrays.fill(mapKey, (byte) seed);
        return new AbsoluteCapability(randomHash(1), randomHash(2), mapKey,
                Optional.of(Bat.random(crypto.random)), SymmetricKey.random(), Optional.empty());
    }

    private static AbsoluteCapability writableCap(int seed) {
        byte[] mapKey = new byte[32];
        Arrays.fill(mapKey, (byte) seed);
        return new WritableAbsoluteCapability(randomHash(1), randomHash(2), mapKey,
                Optional.of(Bat.random(crypto.random)), SymmetricKey.random(), SymmetricKey.random());
    }

    /** The key a link's password derives, so a test can look inside the payload. */
    private static SymmetricKey linkKey(String label, String password) {
        byte[] raw = crypto.hasher.hashToKeyBytes(label, password, EncryptedCapability.LINK_KEY_GENERATOR).join();
        return new TweetNaClKey(raw, false, crypto.symmetricProvider, crypto.random);
    }

    private static CborObject plaintextOf(EncryptedCapability link, String label, String password) {
        return link.payload.decrypt(linkKey(label, password), c -> c);
    }

    /**
     * The compatibility claim, and the reason there is no version field: a link with one member is
     * written as a bare map, exactly as every link written before this was.
     */
    @Test
    public void oneMemberIsWrittenAsABareMap() {
        String label = "abcdefgh", password = "hunter2hunter2";
        AbsoluteCapability cap = readOnlyCap(7);
        EncryptedCapability link = EncryptedCapability
                .createFromPassword(Collections.singletonList(cap), label, password, false, crypto).join();

        CborObject plaintext = plaintextOf(link, label, password);
        Assert.assertTrue("a single member must not be wrapped in a list: " + plaintext.getClass(),
                plaintext instanceof CborObject.CborMap);
        Assert.assertEquals("and it is exactly the capability's own cbor",
                cap.toCbor(), plaintext);
    }

    /** Several members are a list, which is what makes them distinguishable from one. */
    @Test
    public void severalMembersAreWrittenAsAList() {
        String label = "abcdefgh", password = "hunter2hunter2";
        List<AbsoluteCapability> caps = Arrays.asList(readOnlyCap(1), readOnlyCap(2), writableCap(3));
        EncryptedCapability link = EncryptedCapability.createFromPassword(caps, label, password, false, crypto).join();

        Assert.assertTrue(plaintextOf(link, label, password) instanceof CborObject.CborList);
    }

    /**
     * A link written by a client that predates this - a bare map - still resolves, and arrives
     * through the same list returning API as everything else.
     */
    @Test
    public void aLegacyPayloadDecryptsAsAOneMemberList() {
        String label = "abcdefgh", password = "hunter2hunter2";
        AbsoluteCapability cap = writableCap(9);
        // exactly what the old code wrote: the capability, encrypted, with no list around it
        EncryptedCapability legacy = new EncryptedCapability(
                CipherText.build(linkKey(label, password), cap), false);

        List<AbsoluteCapability> decrypted = legacy.decryptFromPassword(label, password, crypto).join();
        Assert.assertEquals(1, decrypted.size());
        Assert.assertEquals(cap, decrypted.get(0));
    }

    /** Members come back in the order they went in, since auto-open and listings depend on it. */
    @Test
    public void membersRoundTripInOrder() {
        String label = "abcdefgh", password = "hunter2hunter2";
        for (int n : new int[]{1, 2, 3, 10, 100}) {
            List<AbsoluteCapability> caps = new ArrayList<>();
            for (int i = 0; i < n; i++)
                caps.add(i % 3 == 0 ? writableCap(i) : readOnlyCap(i));
            EncryptedCapability link = EncryptedCapability.createFromPassword(caps, label, password, false, crypto).join();
            Assert.assertEquals("a link of " + n + " members",
                    caps, link.decryptFromPassword(label, password, crypto).join());
        }
    }

    /** Mixed writability needs no format change: a cap is writable iff it carries a write key. */
    @Test
    public void writabilitySurvivesPerMember() {
        String label = "abcdefgh", password = "hunter2hunter2";
        List<AbsoluteCapability> caps = Arrays.asList(readOnlyCap(1), writableCap(2), readOnlyCap(3));
        List<AbsoluteCapability> back = EncryptedCapability.createFromPassword(caps, label, password, false, crypto)
                .join().decryptFromPassword(label, password, crypto).join();

        Assert.assertFalse(back.get(0).isWritable());
        Assert.assertTrue("exactly the one writable member is writable", back.get(1).isWritable());
        Assert.assertFalse(back.get(2).isWritable());
    }

    /** A link with no members is a bug at the call site, not something to encode. */
    @Test
    public void anEmptyLinkIsRejected() {
        try {
            EncryptedCapability.createFromPassword(Collections.emptyList(), "abcdefgh", "pw", false, crypto).join();
            Assert.fail("should have rejected a link with no capabilities");
        } catch (Exception expected) {
        }
    }
}
