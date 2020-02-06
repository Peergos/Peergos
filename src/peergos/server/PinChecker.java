package peergos.server;

import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;

import java.net.*;
import java.util.*;
import java.util.stream.*;

/** This is utility to check that all the things we think should be pinned on the current node actually are.
 *
 */
public class PinChecker {

    public static void main(String[] args) throws Exception {
        boolean fix = args.length > 0 && args[0].equals("-fix");
        Crypto.initJava();

        IPFS ipfs = new IPFS("localhost", 5001);
        Set<Multihash> allPins = ipfs.pin.ls(IPFS.PinType.recursive).keySet();

        NetworkAccess network = NetworkAccess.buildJava(new URL("http://localhost:8000")).get();
        List<String> usernames = network.coreNode.getUsernames("").join();
        Multihash id = network.dhtClient.id().join();
        for (String username : usernames) {
            List<UserPublicKeyLink> chain = network.coreNode.getChain(username).join();
            if (chain.isEmpty()) {
                System.out.println("Ignoring user with empty chain: " + username);
                continue;
            }
            UserPublicKeyLink current = chain.get(chain.size() - 1);
            Multihash storageNode = current.claim.storageProviders.get(0);
            if (! id.equals(storageNode)) {
                System.out.println("Ignoring user from different server " + username);
                continue;
            }

            PublicKeyHash owner = current.owner;
            try {
                Set<PublicKeyHash> allWriters = WriterData.getOwnedKeysRecursive(owner, owner, network.mutable,
                        network.dhtClient, network.hasher).join();
                Set<Multihash> allRoots = allWriters.stream()
                        .map(w -> network.mutable.getPointerTarget(owner, w, network.dhtClient).join())
                        .filter(m -> m.isPresent())
                        .map(m -> m.get())
                        .collect(Collectors.toSet());

                HashSet<Multihash> missing = new HashSet<>(allRoots);
                missing.removeAll(allPins);
                if (!missing.isEmpty())
                    System.out.println("Missing " + missing.size() + " pins for " + username + " - " + missing);
                if (fix && !missing.isEmpty()) {
                    System.out.println("Adding missing pins...");
                    for (Multihash h : missing) {
                        ipfs.pin.add(h);
                    }
                }
            } catch (Throwable t) {
                System.err.println("Error handling user: " + username);
                t.printStackTrace();
            }
        }
    }
}
