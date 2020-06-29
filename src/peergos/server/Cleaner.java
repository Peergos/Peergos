package peergos.server;

import peergos.shared.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;

public class Cleaner {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = NetworkAccess.buildJava(new URL("https://demo.peergos.net"), true).get();
        String username = args[0];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, network, crypto).get();
        long prior = context.getTotalSpaceUsed().join();
        context.cleanPartialUploads().join();
        long post = context.getTotalSpaceUsed().join();
        System.out.println("Cleaned partial uploads successfully! Reducing space used from " + prior + " to " + post);
    }
}
