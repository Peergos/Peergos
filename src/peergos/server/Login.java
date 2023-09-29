package peergos.server;

import peergos.shared.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;

public class Login {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true).get();
        String username = args[0];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        System.out.println("Logged in " + username + " successfully!");
    }
}
