package peergos.server;

import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class UserStats {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Crypto.initJava();
        NetworkAccess network = NetworkAccess.buildJava(new URL("https://demo.peergos.net")).get();
        List<String> usernames = network.coreNode.getUsernames("").get();
        List<Summary> summaries = usernames.stream().parallel().flatMap(username -> {
            try {
                List<UserPublicKeyLink> chain = network.coreNode.getChain(username).get();
                UserPublicKeyLink last = chain.get(chain.size() - 1);
                LocalDate expiry = last.claim.expiry;
                PublicKeyHash owner = last.owner;
                Set<PublicKeyHash> ownedKeysRecursive = WriterData.getOwnedKeysRecursive(username, network.coreNode, network.mutable, network.dhtClient);
                long total = 0;
                for (PublicKeyHash writer : ownedKeysRecursive) {
                    MaybeMultihash target = network.mutable.getPointerTarget(owner, writer, network.dhtClient).get();
                    if (target.isPresent())
                        total += network.dhtClient.getRecursiveBlockSize(target.get()).get();
                }
                String summary = "User: " + username + ", expiry: " + expiry + " usage: " + total + "\n";
                System.out.println(summary);
                return Stream.of(new Summary(username, expiry, total));
            } catch (Exception e) {
                System.err.println("Error for " + username);
                e.printStackTrace();
                return Stream.empty();
            }
        }).collect(Collectors.toList());

        summaries.sort((a, b) -> (int) (b.usage - a.usage));
        summaries.forEach(System.out::println);
    }

    private static class Summary {
        public final String username;
        public final LocalDate expiry;
        public final long usage;

        public Summary(String username, LocalDate expiry, long usage) {
            this.username = username;
            this.expiry = expiry;
            this.usage = usage;
        }

        public String toString() {
            return "User: " + username + ", expiry: " + expiry + " usage: " + usage;
        }
    }
}
