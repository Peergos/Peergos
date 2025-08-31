package peergos.server;

import peergos.shared.*;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;
import java.util.Optional;

public class Login {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true, Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-login"), Optional.empty()).get();
        String username = args[0];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        System.out.println("Logged in " + username + " successfully!");
    }
}
