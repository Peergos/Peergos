package peergos.server;

import peergos.shared.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.Optional;

public class Renew {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true, Optional.empty()).get();
        String username = args[0];
        LocalDate expiry = LocalDate.parse(args[1]);
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        context.renewUsernameClaim(expiry).get();
        System.out.println("Logged in " + username + " successfully!");
    }
}
