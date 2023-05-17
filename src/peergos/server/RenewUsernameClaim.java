package peergos.server;

import peergos.shared.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;
import java.time.*;

public class RenewUsernameClaim {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true).get();
        String username = args[0];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));

        LocalDate expiry = LocalDate.now().plusMonths(2);
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        boolean isExpired = context.usernameIsExpired().get();
        if (isExpired)
            System.out.println(context.renewUsernameClaim(expiry).get() ? "Renewed username" : "Failed to renew username");
    }
}
