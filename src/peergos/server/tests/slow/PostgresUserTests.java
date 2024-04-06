package peergos.server.tests.slow;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import io.libp2p.crypto.keys.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.peergos.protocol.ipns.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.resolution.*;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

@RunWith(Parameterized.class)
public class PostgresUserTests extends UserTests {
    /** To use this run a local postgres on the default port
     *
     *  sudo apt install postgresql postgresql-contrib
     *
     *  sudo -i -u postgres
     *  psql
     *
     *  create user testuser with encrypted password 'testpassword';
     *  create database peergostest;
     *  grant all privileges on database peergostest to testuser;
     *
     *  # Between test runs
     *  drop database peergostest;create database peergostest;grant all privileges on database peergostest to testuser;
     */
    private static Args args = buildArgs()
            .with("useIPFS", "false")
            .with("use-postgres", "true")
            .with("postgres.host", "localhost")
            .with("postgres.database", "peergostest")
            .with("postgres.username", "testuser")
            .with("postgres.password", "testpassword");

    public PostgresUserTests(NetworkAccess network, UserService service) {
        super(network, service);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        UserService service = Main.PKI_INIT.main(args).localApi;
        WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
        NetworkAccess network = new NetworkAccess(service.coreNode, service.account, service.social, service.storage,
                service.bats, Optional.empty(), service.mutable, mutableTree, synchronizer, service.controller, service.usage, service.serverMessages,
                service.crypto.hasher, Arrays.asList("peergos"), false);
        return Arrays.asList(new Object[][] {
                {network, service}
        });
    }

    @AfterClass
    public static void cleanup() {
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        deleteFiles(peergosDir.toFile());
    }

    @Override
    public Args getArgs() {
        return args;
    }

    @Test
    public void rsaRotation() {
        JdbcServerIdentityStore idstore = JdbcServerIdentityStore.build(Builder.getPostgresConnector(args, ""), new PostgresCommands(), Main.initCrypto());
        // create a new identity
        PrivKey currentPrivate = RsaKt.generateRsaKeyPair(2048).getFirst();
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
