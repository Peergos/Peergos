package peergos.server;

import peergos.server.storage.DelegatingDeletableStorage;
import peergos.server.storage.DeletableContentAddressedStorage;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.CommittedWriterData;
import peergos.shared.user.UserContext;
import peergos.shared.user.WriterData;

import java.io.Console;
import java.net.URL;
import java.util.Optional;
import java.util.Set;

public class LookupOwner {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true, Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-login"), Optional.empty()).get();
        String user = network.coreNode.getUsername(PublicKeyHash.fromString("")).join();
        System.out.println(user);
    }
}
