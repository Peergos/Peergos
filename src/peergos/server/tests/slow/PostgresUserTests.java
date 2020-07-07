package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

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

    public PostgresUserTests(NetworkAccess network) {
        super(network);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        UserService service = Main.PKI_INIT.main(args);
        WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
        NetworkAccess network = new NetworkAccess(service.coreNode, service.social, service.storage,
                service.mutable, mutableTree, synchronizer, service.controller, service.usage, service.serverMessages, Arrays.asList("peergos"), false);
        return Arrays.asList(new Object[][] {
                {network}
        });
    }

    @AfterClass
    public static void cleanup() {
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        deleteFiles(peergosDir.toFile());
    }
}
