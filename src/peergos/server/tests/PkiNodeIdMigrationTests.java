package peergos.server.tests;

import org.junit.*;
import peergos.server.*;

import java.nio.file.*;
import java.util.*;

public class PkiNodeIdMigrationTests {
    private static final String OLD = "QmVdFZgHnEgcedCS2G2ZNiEN59LuVrnRm7z3yXtEBv2XiF";
    private static final String NEW = "12D3KooWFv6ZcoUKyaDBB7nR5SQg6HpmEbDXad48WyFSyEk7xrSR";
    private static final String OTHER = "/dnsaddr/bootstrap.libp2p.io/p2p/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN";
    private static final String DEFAULT_BOOTSTRAP = String.join(",",
            "/ip4/1.2.3.4/tcp/4002/p2p/" + NEW,
            "/ip6/::1/tcp/4002/p2p/" + NEW,
            OTHER);

    private static Path config(String... lines) throws Exception {
        Path f = Files.createTempFile("peergos-config", "");
        f.toFile().deleteOnExit();
        Files.write(f, String.join("\n", lines).getBytes());
        return f;
    }

    @Test
    public void formerIdIsReplaced() throws Exception {
        Path f = config(
                "port = 8000",
                "ipfs-config-bootstrap-node-list = /ip4/1.2.3.4/tcp/4001/p2p/" + OLD + ",/ip6/::1/tcp/4001/p2p/" + OLD + "," + OTHER,
                "pki-node-id = " + OLD);
        Assert.assertTrue(PkiNodeIdMigration.migrateConfig(f, List.of(OLD, NEW), DEFAULT_BOOTSTRAP));
        Assert.assertEquals(List.of(
                "port = 8000",
                "ipfs-config-bootstrap-node-list = " + DEFAULT_BOOTSTRAP,
                "pki-node-id = " + NEW), Files.readAllLines(f));
        Assert.assertFalse(PkiNodeIdMigration.migrateConfig(f, List.of(OLD, NEW), DEFAULT_BOOTSTRAP));
    }

    @Test
    public void otherPkiIsLeftAlone() throws Exception {
        String testPki = "zdvgqGsMfVfU6MCNi6HxgDCuZKuieiRvFmXjri6H8Sdf5B39t";
        Path f = config(
                "ipfs-config-bootstrap-node-list = /ip4/127.0.0.1/tcp/4001/p2p/" + testPki,
                "pki-node-id = " + testPki);
        List<String> before = Files.readAllLines(f);
        Assert.assertFalse(PkiNodeIdMigration.migrateConfig(f, List.of(OLD, NEW), DEFAULT_BOOTSTRAP));
        Assert.assertEquals(before, Files.readAllLines(f));
    }

    @Test
    public void noRotationYet() throws Exception {
        Path f = config("pki-node-id = " + OLD);
        Assert.assertFalse(PkiNodeIdMigration.migrateConfig(f, List.of(OLD), DEFAULT_BOOTSTRAP));
    }
}
