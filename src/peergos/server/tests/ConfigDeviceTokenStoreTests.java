package peergos.server.tests;

import org.junit.*;
import peergos.server.login.*;

import java.nio.file.*;
import java.util.*;

public class ConfigDeviceTokenStoreTests {

    @Test
    public void tokensAreKeptAlongsideTheRestOfTheConfig() throws Exception {
        Path dir = Files.createTempDirectory("peergos-config");
        Path config = dir.resolve("config");
        Files.write(config, List.of("# a comment", "port = 7777", "peergos-url = https://peergos.net"));

        ConfigDeviceTokenStore store = new ConfigDeviceTokenStore(config);
        Assert.assertEquals(Optional.empty(), store.get("alice"));
        store.set("alice", "abc");
        store.set("bob", "def");
        store.set("alice", "123");

        ConfigDeviceTokenStore reopened = new ConfigDeviceTokenStore(config);
        Assert.assertEquals(Optional.of("123"), reopened.get("alice"));
        Assert.assertEquals(Optional.of("def"), reopened.get("bob"));
        Assert.assertEquals(List.of("# a comment", "port = 7777", "peergos-url = https://peergos.net",
                "device-token.alice = 123", "device-token.bob = def"), Files.readAllLines(config));
    }

    @Test
    public void aMissingConfigIsCreated() throws Exception {
        Path config = Files.createTempDirectory("peergos-config").resolve("config");
        new ConfigDeviceTokenStore(config).set("alice", "abc");
        Assert.assertEquals(Optional.of("abc"), new ConfigDeviceTokenStore(config).get("alice"));
    }
}
