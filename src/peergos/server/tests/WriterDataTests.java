package peergos.server.tests;

import org.junit.*;
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
    public void tolerateLoopsInOwnedKeys() throws  Exception {
        Crypto.initJava();
        TransactionId test = new TransactionId("dummy");
        ContentAddressedStorage dht = new RAMStorage();
        Connection db = Sqlite.build("::memory::");
        MutablePointers mutable = UserRepository.build(dht, new JdbcIpnsAndSocial(db, new SqliteCommands()));

        SigningKeyPair pairA = SigningKeyPair.insecureRandom();
        PublicKeyHash pubA = ContentAddressedStorage.hashKey(pairA.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerA = new SigningPrivateKeyAndPublicHash(pubA, pairA.secretSigningKey);

        SigningKeyPair pairB = SigningKeyPair.insecureRandom();
        PublicKeyHash pubB = ContentAddressedStorage.hashKey(pairB.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerB = new SigningPrivateKeyAndPublicHash(pubB, pairB.secretSigningKey);

        WriterData wdA = IpfsTransaction.call(pubA, tid -> WriterData.createEmpty(pubA, signerA, dht, tid), dht).join();
        WriterData wdB = IpfsTransaction.call(pubB, tid -> WriterData.createEmpty(pubB, signerB, dht, tid), dht).join();

        WriterData wdA2 = wdA.addOwnedKey(pubA, signerA, OwnerProof.build(signerB, pubA), dht).join();
        wdA2.commit(pubA, signerA, MaybeMultihash.empty(), mutable, dht, test).join();
        MaybeMultihash bCurrent = wdB.commit(pubB, signerB, MaybeMultihash.empty(), mutable, dht, test).join().get(pubB).hash;

        Set<PublicKeyHash> ownedByA1 = WriterData.getOwnedKeysRecursive(pubA, pubA, mutable, dht).join();
        Set<PublicKeyHash> ownedByB1 = WriterData.getOwnedKeysRecursive(pubB, pubB, mutable, dht).join();

        Assert.assertTrue(ownedByA1.size() == 2);
        Assert.assertTrue(ownedByB1.size() == 1);

        WriterData wdB2 = wdB.addOwnedKey(pubB, signerB, OwnerProof.build(signerA, pubB), dht).join();
        wdB2.commit(pubB, signerB, bCurrent, mutable, dht, test).join();

        Set<PublicKeyHash> ownedByA2 = WriterData.getOwnedKeysRecursive(pubA, pubA, mutable, dht).join();
        Set<PublicKeyHash> ownedByB2 = WriterData.getOwnedKeysRecursive(pubB, pubB, mutable, dht).join();

        Assert.assertTrue(ownedByA2.size() == 2);
        Assert.assertTrue(ownedByB2.size() == 2);
    }
}
