package peergos.server;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.random.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class RenewUsernameClaim {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Crypto.initJava();
        NetworkAccess network = NetworkAccess.buildJava(new URL("https://demo.peergos.net")).get();
        String username = args[0];
        String password = args[1];

        LocalDate expiry = LocalDate.now().plusMonths(2);
        UserContext context = UserContext.signIn(username, password, network, crypto).get();
        boolean isExpired = context.usernameIsExpired().get();
        if (isExpired)
            System.out.println(context.renewUsernameClaim(expiry).get() ? "Renewed username" : "Failed to renew username");
    }
}
