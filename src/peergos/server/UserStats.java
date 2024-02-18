package peergos.server;

import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class UserStats {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true).get();
        List<String> usernames = network.coreNode.getUsernames("").get();
        ForkJoinPool pool = new ForkJoinPool(20);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        List<Summary> summaries = pool.submit(() -> usernames.stream().parallel().flatMap(username -> {
            List<Multihash> hosts = Collections.emptyList();
            try {
                List<UserPublicKeyLink> chain = network.coreNode.getChain(username).get();
                UserPublicKeyLink last = chain.get(chain.size() - 1);
                LocalDate expiry = last.claim.expiry;
                hosts = last.claim.storageProviders;
                PublicKeyHash owner = last.owner;
                Set<PublicKeyHash> ownedKeysRecursive =
                        DeletableContentAddressedStorage.getOwnedKeysRecursive(username, network.coreNode, network.mutable,
                                (h, s) -> ContentAddressedStorage.getWriterData(owner, h, s, network.dhtClient), network.dhtClient, network.hasher).join();
                String summary = "User: " + username + ", expiry: " + expiry
                        + ", owned keys: " + ownedKeysRecursive.size() + "\n";
                System.out.println(summary);
                return Stream.of(new Summary(username, expiry, hosts, ownedKeysRecursive));
            } catch (Exception e) {
                String host = hosts.stream().findFirst().map(Object::toString).orElse("");
                errors.add(username + ": " + host);
                System.err.println("Error for " + username + " on host " + host);
                e.printStackTrace();
                return Stream.empty();
            }
        }).collect(Collectors.toList())).join();

        System.out.println("Errors: " + errors.size());
        errors.forEach(System.out::println);

        // Sort by expiry
        sortAndPrint(summaries, (a, b) -> a.expiry.compareTo(b.expiry), "expiry.txt");

        // Sort by host
        sortAndPrint(summaries, Comparator.comparing(s -> s.storageProviders.stream()
                .findFirst()
                .map(Object::toString)
                .orElse("")), "host.txt");
        pool.shutdownNow();
    }

    private static void sortAndPrint(List<Summary> stats,
                                     Comparator<Summary> order,
                                     String filename) throws Exception {
        stats.sort(order);
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        stats.stream()
                .map(s -> (s.toString() + "\n").getBytes())
                .forEach(bytes -> bout.write(bytes, 0, bytes.length));
        Files.write(PathUtil.get(filename), bout.toByteArray());
    }

    private static class Summary {
        public final String username;
        public final LocalDate expiry;
        public final List<Multihash> storageProviders;
        public final Set<PublicKeyHash> ownedKeys;

        public Summary(String username, LocalDate expiry, List<Multihash> storageProviders, Set<PublicKeyHash> ownedKeys) {
            this.username = username;
            this.expiry = expiry;
            this.storageProviders = storageProviders;
            this.ownedKeys = ownedKeys;
        }

        public String toString() {
            return "User: " + username + ", expiry: " + expiry + ", hosts: " + storageProviders
                    + ", owned keys: " + ownedKeys.size();
        }
    }
}
