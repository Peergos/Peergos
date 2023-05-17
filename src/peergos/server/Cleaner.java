package peergos.server;

import peergos.shared.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;

public class Cleaner {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true).get();
        String username = args[0];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        long prior = context.getSpaceUsage().join();
        context.cleanPartialUploads().join();
        long post = context.getSpaceUsage().join();
        System.out.println("Cleaned partial uploads successfully! Reducing space used from " + prior + " to " + post);
    }
}
