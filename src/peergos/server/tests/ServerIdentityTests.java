package peergos.server.tests;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import io.libp2p.crypto.keys.*;
import org.junit.*;
import org.peergos.protocol.ipns.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.resolution.*;
import peergos.shared.util.*;

import java.nio.charset.*;
import java.util.*;

public class ServerIdentityTests {

    @Test
    public void rotation() {
        JdbcServerIdentityStore idstore = JdbcServerIdentityStore.build(Builder.buildEphemeralSqlite(), new SqliteCommands(), Main.initCrypto());
        // create a new identity
        PrivKey currentPrivate = Ed25519Kt.generateEd25519KeyPair().getFirst();
        byte[] signedRecord = ServerIdentity.generateSignedIpnsRecord(currentPrivate, Optional.empty(), false,  1);
        idstore.addIdentity(PeerId.fromPubKey(currentPrivate.publicKey()), signedRecord);

        List<PeerId> ids = idstore.getIdentities();
        Assert.assertEquals(1, ids.size());
        PeerId current = ids.get(0);
        Assert.assertEquals(current, PeerId.fromPubKey(currentPrivate.publicKey()));

        String password = Passwords.generate();
        Crypto crypto = Main.initCrypto();
        PrivKey nextPriv = ServerIdentity.generateNextIdentity(password, current, crypto);
        PeerId nextPeerId = PeerId.fromPubKey(nextPriv.publicKey());
        idstore.setPrivateKey(currentPrivate);
        idstore.setRecord(current, ServerIdentity.generateSignedIpnsRecord(currentPrivate, Optional.of(Multihash.decode(nextPeerId.getBytes())), true,2));
        idstore.addIdentity(nextPeerId, ServerIdentity.generateSignedIpnsRecord(nextPriv, Optional.empty(), false, 1));

        List<PeerId> updated = idstore.getIdentities();
        Assert.assertEquals(2, updated.size());
        Assert.assertEquals(nextPeerId, updated.get(1));
        byte[] prevRecord = idstore.getRecord(current);
        Optional<IpnsMapping> prevIpnsMapping = IPNS.parseAndValidateIpnsEntry(
                ArrayOps.concat("/ipns/".getBytes(StandardCharsets.UTF_8), current.getBytes()),
                prevRecord);
        ResolutionRecord prevRes = ResolutionRecord.fromCbor(CborObject.fromByteArray(prevIpnsMapping.get().value.value));
        Assert.assertEquals(prevRes.moved, true);
        Assert.assertEquals(prevRes.host, Optional.of(Multihash.fromBase58(nextPeerId.toBase58())));
    }
}
