package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;

import java.util.*;

/** Anyone can send a follow request, and a malformed one used to stop its target logging in. */
public class FollowRequestValidationTests {

    @Test
    public void malformedRequestsAreRejectedAndSkipped() {
        Crypto crypto = Main.initCrypto();
        JdbcIpnsAndSocial store = new JdbcIpnsAndSocial(Main.buildEphemeralSqlite(), new SqliteCommands());
        UserRepository social = UserRepository.build(new RAMStorage(crypto.hasher), store, crypto.hasher);
        SigningKeyPair target = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash owner = ContentAddressedStorage.hashKey(target.publicSigningKey);

        byte[] junk = new CborObject.CborLong(0).serialize();
        Assert.assertFalse(social.sendFollowRequest(owner, junk).join());

        BoxingKeyPair targetBoxer = BoxingKeyPair.randomCurve25519(crypto.random, crypto.boxer);
        BlindFollowRequest valid = BlindFollowRequest.build(targetBoxer.publicBoxingKey,
                new FollowRequest(Optional.empty(), Optional.empty()), crypto).join();
        Assert.assertTrue(social.sendFollowRequest(owner, valid.serialize()).join());

        // as stored by a server from before requests were checked on arrival
        store.addFollowRequest(owner, junk).join();
        List<BlindFollowRequest> reqs = store.getAndParseFollowRequests(owner);
        Assert.assertEquals(1, reqs.size());
        Assert.assertArrayEquals(valid.serialize(), reqs.get(0).serialize());
    }
}
