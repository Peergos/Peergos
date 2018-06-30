package peergos.server;
import java.util.logging.*;

import peergos.shared.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;

public class Login {
	private static final Logger LOG = Logger.getGlobal();

    public static void main(String[] args) throws Exception {
        Crypto crypto = Crypto.initJava();
        NetworkAccess network = NetworkAccess.buildJava(new URL("https://demo.peergos.net")).get();
        String username = args[0];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, network, crypto).get();
        LOG.info("Logged in " + username + " successfully!");
    }
}
