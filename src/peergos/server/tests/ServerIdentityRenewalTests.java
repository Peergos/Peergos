package peergos.server.tests;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import io.libp2p.crypto.keys.*;
import org.junit.*;
import org.peergos.cbor.CborObject;
import org.peergos.protocol.ipns.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.resolution.*;
import peergos.shared.storage.IpnsEntry;

import java.nio.charset.*;
import java.time.*;
import java.util.*;

/** An expired server identity record, like the RSA pki node's, is renewed rather than wedging the publisher. */
public class ServerIdentityRenewalTests {

    @Test
    public void expiredRsaRecordIsRenewed() throws Exception {
        PrivKey rsaPrivate = RsaKt.generateRsaKeyPair(2048).getFirst();
        PeerId rsaId = PeerId.fromPubKey(rsaPrivate.publicKey());
        ResolutionRecord rr = new ResolutionRecord(Optional.empty(), false, Optional.empty(), 3);
        byte[] expired = IPNS.createSignedRecord("/ipfs/bafkqaaa".getBytes(StandardCharsets.UTF_8),
                LocalDateTime.now().minusMonths(19), 3, 365L * 24 * 3600 * 1000_000_000,
                Optional.of(IpnsEntry.RESOLUTION_RECORD_IPNS_SUFFIX), Optional.of(CborObject.fromByteArray(rr.serialize())), rsaPrivate);
        byte[] ipnsKey = IPNS.getKey(io.ipfs.multihash.Multihash.deserialize(rsaId.getBytes()));
        Assert.assertTrue(IPNS.parseAndValidateIpnsEntry(ipnsKey, expired).isEmpty());

        // the key is supplied on the command line and isn't in the db, as on the pki node
        Args args = UserTests.buildArgs()
                .with("useIPFS", "true")
                .with("async-bootstrap", "true")
                .with("ipfs.identity.priv-key", Base64.getEncoder().encodeToString(rsaPrivate.bytes()))
                .with("ipfs.identity.peerid", rsaId.toBase58())
                .with("serverids-file", "serverids.sql");
        JdbcServerIdentityStore ids = JdbcServerIdentityStore.build(Builder.getDBConnector(args, "serverids-file"),
                new SqliteCommands(), Main.initCrypto());
        ids.addIdentity(rsaId, expired);

        Assert.assertEquals(3, ServerIdentity.storedValue(expired).sequence);

        IpfsWrapper ipfs = Main.IPFS.main(args);
        try {
            Optional<IpnsMapping> renewed = Optional.empty();
            for (int i = 0; i < 60 && renewed.isEmpty(); i++) {
                renewed = IPNS.parseAndValidateIpnsEntry(ipnsKey, ids.getRecord(rsaId));
                if (renewed.isEmpty())
                    Thread.sleep(500);
            }
            Assert.assertTrue("record was not renewed", renewed.isPresent());
            Assert.assertTrue(renewed.get().value.expiry.isAfter(LocalDateTime.now().plusMonths(11)));
            ResolutionRecord after = ServerIdentity.storedValue(ids.getRecord(rsaId));
            Assert.assertEquals(4, after.sequence);
            Assert.assertFalse(after.moved);
            Assert.assertTrue(after.host.isEmpty());
        } finally {
            ipfs.stop();
        }
    }
}
