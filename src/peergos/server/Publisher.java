package peergos.server;

import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.net.*;

/**
 * Make a file or directory in Peergos public
 */
public class Publisher {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true).get();
        String username = args[0];
        String pathToMakePublic = args[1];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        FileWrapper file = context.getByPath(pathToMakePublic).join().get();
        context.makePublic(file).join();
        System.out.println("Made " + pathToMakePublic + " public.");
    }
}
