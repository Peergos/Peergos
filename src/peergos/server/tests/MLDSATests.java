package peergos.server.tests;

import org.junit.*;
import peergos.server.Main;
import peergos.server.crypto.asymmetric.mldsa.*;
import peergos.shared.Crypto;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.asymmetric.mldsa.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;

public class MLDSATests {
    private static Crypto crypto = Main.initCrypto();
    private static final SigningDomain D = SigningDomain.POINTER;

    private static SigningKeyPair random() {
        return SigningKeyPair.randomHybrid(crypto.random, crypto.signer, crypto.mldsa);
    }

    @Test
    public void providerIsJdk() {
        Assert.assertTrue(crypto.mldsa instanceof JdkMldsa);
    }

    @Test
    public void keygenIsDeterministicFromSeed() {
        byte[] seed = crypto.random.randomBytes(Mldsa.SEED_BYTES);
        byte[] pk = crypto.mldsa.publicKey(seed);
        Assert.assertEquals(Mldsa.PUBLIC_KEY_BYTES, pk.length);
        Assert.assertArrayEquals(pk, crypto.mldsa.publicKey(seed));
    }

    @Test
    public void signAndVerify() {
        SigningKeyPair pair = random();
        byte[] message = crypto.random.randomBytes(100);
        byte[] signed = pair.secretSigningKey.signMessage(message, D).join();
        Assert.assertEquals(HybridEd25519MLDSAPublicKey.SIGNATURE_SIZE_BYTES + message.length, signed.length);
        Assert.assertArrayEquals(message, pair.publicSigningKey.unsignMessage(signed, D).join());

        byte[] detached = pair.secretSigningKey.signatureOnly(message, D).join();
        Assert.assertArrayEquals(message, pair.publicSigningKey.unsignMessage(ArrayOps.concat(detached, message), D).join());
    }

    @Test
    public void rejectsTampering() {
        SigningKeyPair pair = random();
        byte[] message = "hello".getBytes();
        byte[] signed = pair.secretSigningKey.signMessage(message, D).join();
        for (int index : new int[]{0, 70, HybridEd25519MLDSAPublicKey.SIGNATURE_SIZE_BYTES}) {
            byte[] bad = Arrays.copyOf(signed, signed.length);
            bad[index] ^= 1;
            assertInvalid(pair.publicSigningKey, bad);
        }
        assertInvalid(pair.publicSigningKey, Arrays.copyOf(signed, HybridEd25519MLDSAPublicKey.SIGNATURE_SIZE_BYTES - 1));
        assertInvalid(random().publicSigningKey, signed);
    }

    @Test
    public void componentsCannotBeUsedAlone() {
        SigningKeyPair pair = random();
        HybridEd25519MLDSAPublicKey pub = (HybridEd25519MLDSAPublicKey) pair.publicSigningKey;
        byte[] message = "hello".getBytes();
        byte[] signed = pair.secretSigningKey.signMessage(message, D).join();

        // the ed25519 part signs the domain separated message, not the message
        byte[] edOnly = ArrayOps.concat(Arrays.copyOf(signed, Ed25519PublicKey.SIGNATURE_SIZE_BYTES), message);
        assertInvalid(pub.ed25519, edOnly);

        // a plain ed25519 signature from the component key doesn't verify as a composite one
        HybridEd25519MLDSASecretKey secret = (HybridEd25519MLDSASecretKey) pair.secretSigningKey;
        byte[] edSigned = secret.ed25519.signMessage(message, D).join();
        assertInvalid(pub, ArrayOps.concat(ArrayOps.concat(Arrays.copyOf(edSigned, 64), new byte[Mldsa.SIGNATURE_BYTES]), message));
    }

    @Test
    public void domainsSeparate() {
        SigningKeyPair pair = random();
        byte[] message = "hello".getBytes();
        for (SigningDomain signedIn : SigningDomain.values()) {
            byte[] signed = pair.secretSigningKey.signMessage(message, signedIn).join();
            for (SigningDomain verifiedIn : SigningDomain.values()) {
                if (verifiedIn == signedIn)
                    Assert.assertArrayEquals(message, pair.publicSigningKey.unsignMessage(signed, verifiedIn).join());
                else
                    assertInvalid(pair.publicSigningKey, signed, verifiedIn);
            }
        }
    }

    @Test
    public void ed25519IgnoresDomain() {
        SigningKeyPair ed = SigningKeyPair.random(crypto.random, crypto.signer);
        byte[] message = "hello".getBytes();
        byte[] signed = ed.secretSigningKey.signMessage(message, SigningDomain.BLOCK).join();
        Assert.assertArrayEquals(signed, ed.secretSigningKey.signMessage(message, SigningDomain.POINTER).join());
        Assert.assertArrayEquals(message, ed.publicSigningKey.unsignMessage(signed, SigningDomain.OWNER_PROOF).join());
    }

    @Test
    public void serialization() {
        SigningKeyPair pair = random();
        SigningKeyPair decoded = SigningKeyPair.fromByteArray(pair.serialize());
        Assert.assertEquals(pair, decoded);
        Assert.assertEquals(pair.publicSigningKey, PublicSigningKey.fromByteArray(pair.publicSigningKey.serialize()));
        Assert.assertEquals(pair.secretSigningKey, SecretSigningKey.fromCbor(pair.secretSigningKey.toCbor()));

        byte[] message = "hello".getBytes();
        byte[] signed = decoded.secretSigningKey.signMessage(message, D).join();
        Assert.assertArrayEquals(message, pair.publicSigningKey.unsignMessage(signed, D).join());
        // the secret is the seeds, not the expanded key
        Assert.assertTrue(pair.secretSigningKey.serialize().length < 128);
    }

    @Test
    public void keyHash() {
        SigningKeyPair pair = random();
        PublicKeyHash hash = ContentAddressedStorage.hashKey(pair.publicSigningKey);
        Assert.assertFalse(hash.isIdentity());
        Assert.assertEquals(Multihash.Type.sha2_256, hash.type);
        Assert.assertEquals(Cid.Codec.DagCbor, hash.codec);
        Assert.assertArrayEquals(Hash.sha256(pair.publicSigningKey.serialize()), hash.getHash());
        Assert.assertEquals(hash, PublicKeyHash.fromString(hash.toString()));

        SigningKeyPair ed = SigningKeyPair.random(crypto.random, crypto.signer);
        Assert.assertTrue(ContentAddressedStorage.hashKey(ed.publicSigningKey).isIdentity());
    }

    private static void assertInvalid(PublicSigningKey key, byte[] signed) {
        assertInvalid(key, signed, D);
    }

    private static void assertInvalid(PublicSigningKey key, byte[] signed, SigningDomain domain) {
        try {
            key.unsignMessage(signed, domain).join();
        } catch (Throwable t) {
            return;
        }
        Assert.fail("Invalid signature accepted");
    }
}
