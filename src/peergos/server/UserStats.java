package peergos.server;

import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;

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
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://alpha.peergos.net"), true).get();
        List<String> usernames = network.coreNode.getUsernames("").get();
        ForkJoinPool pool = new ForkJoinPool(20);
        List<Summary> summaries = pool.submit(() -> usernames.stream().parallel().flatMap(username -> {
            try {
                List<UserPublicKeyLink> chain = network.coreNode.getChain(username).get();
                UserPublicKeyLink last = chain.get(chain.size() - 1);
                LocalDate expiry = last.claim.expiry;
                List<Multihash> hosts = last.claim.storageProviders;
                PublicKeyHash owner = last.owner;
                Set<PublicKeyHash> ownedKeysRecursive =
                        WriterData.getOwnedKeysRecursive(username, network.coreNode, network.mutable,
                                network.dhtClient, network.hasher).join();
                long total = 0;
                for (PublicKeyHash writer : ownedKeysRecursive) {
                    MaybeMultihash target = network.mutable.getPointerTarget(owner, writer, network.dhtClient).get();
                    if (target.isPresent())
                        total += network.dhtClient.getRecursiveBlockSize(target.get()).get();
                }
                String summary = "User: " + username + ", expiry: " + expiry + " usage: " + total
                        + ", owned keys: " + ownedKeysRecursive.size() + "\n";
                System.out.println(summary);
                return Stream.of(new Summary(username, expiry, total, hosts, ownedKeysRecursive));
            } catch (Exception e) {
                System.err.println("Error for " + username);
                e.printStackTrace();
                return Stream.empty();
            }
        }).collect(Collectors.toList())).join();

        // Sort by usage
        sortAndPrint(summaries, (a, b) -> Long.compare(b.usage, a.usage), "usage.txt");

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
        Files.write(Paths.get(filename), bout.toByteArray());
    }

    private static class Summary {
        public final String username;
        public final LocalDate expiry;
        public final long usage;
        public final List<Multihash> storageProviders;
        public final Set<PublicKeyHash> ownedKeys;

        public Summary(String username, LocalDate expiry, long usage, List<Multihash> storageProviders, Set<PublicKeyHash> ownedKeys) {
            this.username = username;
            this.expiry = expiry;
            this.usage = usage;
            this.storageProviders = storageProviders;
            this.ownedKeys = ownedKeys;
        }

        public String toString() {
            return "User: " + username + ", expiry: " + expiry + ", usage: " + usage
                    + ", hosts: " + storageProviders + ", owned keys: " + ownedKeys.size();
        }
    }
}
