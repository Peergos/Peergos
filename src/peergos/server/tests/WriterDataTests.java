package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.sql.*;
import java.util.*;

public class WriterDataTests {

    @Test
    public void tolerateLoopsInOwnedKeys() {
        Crypto crypto = Main.initCrypto();
        Hasher hasher = crypto.hasher;
        DeletableContentAddressedStorage dht = new RAMStorage(hasher);
        MutablePointers mutable = UserRepository.build(dht, new JdbcIpnsAndSocial(Main.buildEphemeralSqlite(), new SqliteCommands()), hasher);

        SigningKeyPair pairA = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash pubA = ContentAddressedStorage.hashKey(pairA.publicSigningKey);
        TransactionId test = dht.startTransaction(pubA).join();
        SigningPrivateKeyAndPublicHash signerA = new SigningPrivateKeyAndPublicHash(pubA, pairA.secretSigningKey);

        SigningKeyPair pairB = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash pubB = ContentAddressedStorage.hashKey(pairB.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerB = new SigningPrivateKeyAndPublicHash(pubB, pairB.secretSigningKey);

        WriterData wdA = IpfsTransaction.call(pubA, tid -> WriterData.createEmpty(pubA, signerA, dht, hasher, tid), dht).join();
        WriterData wdB = IpfsTransaction.call(pubA, tid -> WriterData.createEmpty(pubA, signerB, dht, hasher, tid), dht).join();

        WriterData wdA2 = wdA.addOwnedKey(pubA, signerA, OwnerProof.build(signerB, pubA).join(), dht, hasher).join();
        wdA2.commit(pubA, signerA, MaybeMultihash.empty(), Optional.empty(), mutable, dht, hasher, test).join();
        CommittedWriterData bCurrentCwd = wdB.commit(pubA, signerB, MaybeMultihash.empty(), Optional.empty(), mutable, dht, hasher, test).join().get(pubB);

        CommittedWriterData.Retriever retriever = (h, s) -> DeletableContentAddressedStorage.getWriterData(Collections.emptyList(), pubA, h, s, false, dht.id().join(), hasher, dht);
        Set<PublicKeyHash> ownedByA1 = DeletableContentAddressedStorage.getOwnedKeysRecursive(pubA, pubA, mutable, retriever, dht, hasher).join();
        Set<PublicKeyHash> ownedByB1 = DeletableContentAddressedStorage.getOwnedKeysRecursive(pubA, pubB, mutable, retriever, dht, hasher).join();

        Assert.assertTrue(ownedByA1.size() == 2);
        Assert.assertTrue(ownedByB1.size() == 1);

        MaybeMultihash bCurrent = bCurrentCwd.hash;
        WriterData wdB2 = wdB.addOwnedKey(pubA, signerB, OwnerProof.build(signerA, pubB).join(), dht, hasher).join();
        wdB2.commit(pubA, signerB, bCurrent, bCurrentCwd.sequence, mutable, dht, hasher, test).join();

        Set<PublicKeyHash> ownedByA2 = DeletableContentAddressedStorage.getOwnedKeysRecursive(pubA, pubA, mutable, retriever, dht, hasher).join();
        Set<PublicKeyHash> ownedByB2 = DeletableContentAddressedStorage.getOwnedKeysRecursive(pubA, pubB, mutable, retriever, dht, hasher).join();

        Assert.assertTrue(ownedByA2.size() == 2);
        Assert.assertTrue(ownedByB2.size() == 2);
    }

    @Test
    public void ownsKeyWithOrphanedAndLoopedKeys() {
        Crypto crypto = Main.initCrypto();
        Hasher hasher = crypto.hasher;
        DeletableContentAddressedStorage dht = new RAMStorage(hasher);
        MutablePointers mutable = UserRepository.build(dht, new JdbcIpnsAndSocial(Main.buildEphemeralSqlite(), new SqliteCommands()), hasher);

        SigningKeyPair pairA = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash pubA = ContentAddressedStorage.hashKey(pairA.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerA = new SigningPrivateKeyAndPublicHash(pubA, pairA.secretSigningKey);
        TransactionId tid = dht.startTransaction(pubA).join();

        SigningKeyPair pairB = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash pubB = ContentAddressedStorage.hashKey(pairB.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerB = new SigningPrivateKeyAndPublicHash(pubB, pairB.secretSigningKey);

        // authorised, but never written to
        SigningKeyPair pairC = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash pubC = ContentAddressedStorage.hashKey(pairC.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerC = new SigningPrivateKeyAndPublicHash(pubC, pairC.secretSigningKey);

        SigningKeyPair pairD = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash pubD = ContentAddressedStorage.hashKey(pairD.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerD = new SigningPrivateKeyAndPublicHash(pubD, pairD.secretSigningKey);

        SigningKeyPair pairE = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash pubE = ContentAddressedStorage.hashKey(pairE.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerE = new SigningPrivateKeyAndPublicHash(pubE, pairE.secretSigningKey);

        PublicKeyHash unrelated = ContentAddressedStorage.hashKey(SigningKeyPair.random(crypto.random, crypto.signer).publicSigningKey);

        WriterData wdE = IpfsTransaction.call(pubA, t -> WriterData.createEmpty(pubA, signerE, dht, hasher, t), dht).join();
        wdE.commit(pubA, signerE, MaybeMultihash.empty(), Optional.empty(), mutable, dht, hasher, tid).join();

        WriterData wdD = IpfsTransaction.call(pubA, t -> WriterData.createEmpty(pubA, signerD, dht, hasher, t), dht).join()
                .addOwnedKey(pubA, signerD, OwnerProof.build(signerE, pubD).join(), dht, hasher).join();
        wdD.commit(pubA, signerD, MaybeMultihash.empty(), Optional.empty(), mutable, dht, hasher, tid).join();

        WriterData wdB = IpfsTransaction.call(pubA, t -> WriterData.createEmpty(pubA, signerB, dht, hasher, t), dht).join()
                .addOwnedKey(pubA, signerB, OwnerProof.build(signerC, pubB).join(), dht, hasher).join()
                .addOwnedKey(pubA, signerB, OwnerProof.build(signerD, pubB).join(), dht, hasher).join()
                .addOwnedKey(pubA, signerB, OwnerProof.build(signerA, pubB).join(), dht, hasher).join();
        wdB.commit(pubA, signerB, MaybeMultihash.empty(), Optional.empty(), mutable, dht, hasher, tid).join();

        WriterData wdA = IpfsTransaction.call(pubA, t -> WriterData.createEmpty(pubA, signerA, dht, hasher, t), dht).join()
                .addOwnedKey(pubA, signerA, OwnerProof.build(signerB, pubA).join(), dht, hasher).join();
        wdA.commit(pubA, signerA, MaybeMultihash.empty(), Optional.empty(), mutable, dht, hasher, tid).join();

        Assert.assertTrue(mutable.getPointer(pubA, pubC).join().isEmpty());

        // a writer with no pointer must not stop the search, and a loop must not make it run forever
        Assert.assertTrue(wdA.ownsKey(pubA, pubE, dht, mutable, hasher).join());
        Assert.assertTrue(wdA.ownsKey(pubA, pubD, dht, mutable, hasher).join());
        Assert.assertTrue(wdA.ownsKey(pubA, pubC, dht, mutable, hasher).join());
        Assert.assertFalse(wdA.ownsKey(pubA, unrelated, dht, mutable, hasher).join());
    }
}
