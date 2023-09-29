package peergos.server;

import peergos.shared.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;

public class Login {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("http://localhost:8000"), false).get();
        Console console = System.console();
        String username = /*"peergos";/*/"tester-u001";//args[0];
//        String username = "peergos";//"tester-u001";//args[0];
        String password = /*"test-peergos-password-dog";/*/"force-permit-fish-twelve-obscure-rice-provide";//new String(console.readPassword("Enter password for " + username + ":"));
//        String password = "test-peergos-password-dog";//"force-permit-fish-twelve-obscure-rice-provide";//new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        System.out.println("Logged in " + username + " successfully!");
    }
}
