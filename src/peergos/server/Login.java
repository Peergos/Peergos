package peergos.server;

import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.stream.*;

public class Login {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://beta.peergos.net"), true).get();
        List<String> all = network.coreNode.getUsernames("").join();
        List<Pair<String, UserPublicKeyLink>> allPairs = all.stream()
                .flatMap(n -> network.coreNode.getChain(n).join()
                        .stream().map(l -> new Pair<>(n, l)))
                .collect(Collectors.toList());
        List<Pair<String, Multihash>> allHosts = allPairs.stream()
                .flatMap(p -> p.right.claim.storageProviders.stream()
                        .map(s -> new Pair<>(p.left, s))).collect(Collectors.toList());
        Map<Multihash, List<Pair<String, Multihash>>> byHost = allHosts.stream()
                .collect(Collectors.groupingBy(p -> p.right));
        List<Pair<String, UserPublicKeyLink>> reachable = allPairs.stream()
                .parallel()
                .filter(e -> network.mutable.getPointerTarget(e.right.owner, e.right.owner, network.dhtClient)
                        .exceptionally(t -> MaybeMultihash.empty()).join().isPresent())
                .collect(Collectors.toList());
        Map<Multihash, List<Pair<String, Multihash>>> byHostReachable = reachable.stream()
                .flatMap(p -> p.right.claim.storageProviders.stream().map(s -> new Pair<>(p.left, s)))
                .collect(Collectors.groupingBy(p -> p.right));
        byHostReachable.entrySet().forEach(e -> {
            System.out.println(e.getKey() + " " + e.getValue().size());
            if (e.getValue().size() < 1000)
                e.getValue().forEach(p -> System.out.println("   " + p.left));
        });
        System.out.println();
//        String username = args[0];
//        Console console = System.console();
//        String password = new String(console.readPassword("Enter password for " + username + ":"));
//        UserContext context = UserContext.signIn(username, password, network, crypto).get();
//        System.out.println("Logged in " + username + " successfully!");
    }
}
