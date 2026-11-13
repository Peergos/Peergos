package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** The owned-keys walk ignores a proof that doesn't verify, but not a key it can't read: that has to fail the walk, or
 *  the usage accounting and the garbage collector would take the key's space as nobody's and free it. Keys made before
 *  keys were inlined are blocks of their own, so a failed read of one is an ordinary storage error. */
public class OwnedKeysWalkTests {
    private static final Crypto crypto = Main.initCrypto();

    private final RAMStorage storage = new RAMStorage(crypto.hasher);
    private final SigningKeyPair ownerPair = SigningKeyPair.random(crypto.random, crypto.signer);
    private final PublicKeyHash owner = ContentAddressedStorage.hashKey(ownerPair.publicSigningKey);
    private final SigningKeyPair writerPair = SigningKeyPair.random(crypto.random, crypto.signer);
    private final PublicKeyHash writer = ContentAddressedStorage.hashKey(writerPair.publicSigningKey);
    private final TransactionId tid = storage.startTransaction(owner).join();

    /** A key stored as a block of its own, as keys once were, with a valid proof that the writer owns it */
    private OwnerProof oldStyleOwnedKey() {
        SigningKeyPair child = SigningKeyPair.random(crypto.random, crypto.signer);
        Cid block = storage.put(owner, writer, List.of(new byte[0]), List.of(child.publicSigningKey.serialize()),
                tid).join().get(0);
        PublicKeyHash hashed = new PublicKeyHash(block);
        Assert.assertFalse(hashed.isIdentity());
        return OwnerProof.build(new SigningPrivateKeyAndPublicHash(hashed, child.secretSigningKey), writer).join();
    }

    private CompletableFuture<Set<PublicKeyHash>> walk(OwnerProof named, ContentAddressedStorage from) {
        WriterData root = new WriterData(writer, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of("child", named), Optional.empty(), Optional.empty(), Optional.empty());
        Cid rootHash = storage.put(owner, writer, List.of(new byte[0]), List.of(root.serialize()),
                tid).join().get(0);
        return DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writer, MaybeMultihash.of(rootHash),
                (h, seq) -> Futures.of(new CommittedWriterData(MaybeMultihash.of(h), root, seq)),
                w -> Futures.of(Optional.empty()), from, crypto.hasher);
    }

    @Test
    public void anOldStyleKeyIsOwned() {
        OwnerProof proof = oldStyleOwnedKey();
        Assert.assertEquals(Set.of(proof.ownedKey), walk(proof, storage).join());
    }

    @Test
    public void anOwnedKeyThatCantBeReadFailsTheWalk() {
        OwnerProof proof = oldStyleOwnedKey();
        ContentAddressedStorage failing = new DelegatingStorage(storage) {
            @Override
            public ContentAddressedStorage directToOrigin() {
                return this;
            }

            @Override
            public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash o, Cid hash, Optional<BatWithId> bat) {
                return hash.equals(proof.ownedKey.target) ? Futures.errored(new IllegalStateException("Storage unreachable")) :
                        super.getRaw(o, hash, bat);
            }

            @Override
            public CompletableFuture<Optional<CborObject>> get(PublicKeyHash o, Cid hash, Optional<BatWithId> bat) {
                return hash.equals(proof.ownedKey.target) ? Futures.errored(new IllegalStateException("Storage unreachable")) :
                        super.get(o, hash, bat);
            }
        };
        try {
            Set<PublicKeyHash> owned = walk(proof, failing).join();
            Assert.fail("A key that couldn't be read was taken as not owned: " + owned);
        } catch (CompletionException expected) {
        }
    }

    @Test
    public void aBlockThatIsntAKeyIsNotOwned() {
        OwnerProof real = oldStyleOwnedKey();
        Cid notAKey = storage.put(owner, writer, List.of(new byte[0]), List.of(new CborObject.CborString("not a key").serialize()),
                tid).join().get(0);
        OwnerProof forged = new OwnerProof(new PublicKeyHash(notAKey), real.signedOwner);
        Assert.assertEquals(Set.of(), walk(forged, storage).join());
    }
}
