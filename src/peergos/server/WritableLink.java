package peergos.server;

import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class WritableLink {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true).get();
        String username = args[0];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        System.out.println("Logged in " + username + " successfully!");
        System.out.println("Enter path you wish to generate a writable secret link for (e.g. /demo/stuff/photos/vacation):");
        String path = console.readLine();
        System.out.println("Are you sure you want to generate a writable link? Anyone with this link can fill your space allowance. (y/n)");
        if (! "y".equals(console.readLine())) {
            System.out.println("Aborting..");
            return;
        }
        if (! path.startsWith("/" + username)) {
            System.out.println("You can only generate a writable link to a file/folder you own.");
            return;
        }

        // move folder to new signing subspace
        System.out.println("Moving file/folder to new writing space");
        context.shareWriteAccessWith(PathUtil.get(path), Collections.emptySet()).join();

        // generate link
        FileWrapper file = context.getByPath(path).join().get();
        String link = file.writableFilePointer().toLink();
        System.out.println("***** Writable secret link, only share with people you trust *****");
        System.out.println("https://peergos.net/#" + URLEncoder.encode("{\"secretLink\":true,\"open\":true,\"link\":\""+link.substring(1) + "\"}"));
    }
}
