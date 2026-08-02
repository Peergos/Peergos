package peergos.server.tests.util;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Checks the owned key graph of a user is well formed.
 *
 *  This lives in its own class, rather than on a test class, deliberately: it is
 *  called from UserTests and PeergosNetworkUtils, and hanging it off a test class
 *  would make every caller trigger that class's static initialiser — starting a
 *  server and allocating ports it never uses, and turning a single port clash into
 *  a NoClassDefFoundError for every later test in the calling class. */
public class UserValidity {

    public static void checkUserValidity(NetworkAccess network, String username) {
        PublicKeyHash identity = network.coreNode.getPublicKeyHash(username).join().get();
        checkUserValidity(1, identity, identity, Collections.emptySet(), network);
    }

    public static void checkUserValidity(int maxClaims,
                                         PublicKeyHash owner,
                                         PublicKeyHash writer,
                                         Set<PublicKeyHash> ancestors,
                                         NetworkAccess network) {
        WriterData props = WriterData.getWriterData(owner, writer, network.mutable, network.dhtClient).join().props.get();
        if (! props.ownedKeys.isPresent())
            return;
        OwnedKeyChamp ownedChamp = props.getOwnedKeyChamp(owner, network.dhtClient, network.hasher).join();
        Set<OwnerProof> empty = Collections.emptySet();
        Set<OwnerProof> claims = ownedChamp.applyToAllMappings(owner, empty,
                (a, b) -> CompletableFuture.completedFuture(Stream.concat(a.stream(), Stream.of(b.right)).collect(Collectors.toSet())),
                network.dhtClient).join();
        Set<PublicKeyHash> ownedKeys = claims.stream()
                .map(p -> p.ownedKey)
                .collect(Collectors.toSet());
        Set<Pair<PublicKeyHash, PublicKeyHash>> pairs = claims.stream()
                .map(p -> new Pair<>(p.getAndVerifyOwner(owner, network.dhtClient).join(), p.ownedKey))
                .collect(Collectors.toSet());
        Set<PublicKeyHash> ownerKeys = pairs.stream()
                .map(p -> p.left)
                .collect(Collectors.toSet());
        if (claims.size() > maxClaims)
            throw new IllegalStateException("Too many owned keys on identity key pair for " + writer);
        if (! ownerKeys.isEmpty() && ownerKeys.size() != 1)
            throw new IllegalStateException("More than 1 owner key on writer data for " + writer);
        if (! ownerKeys.isEmpty() && ! ownerKeys.contains(writer))
            throw new IllegalStateException("WriterData contains claims with wrong owner for " + writer);
        if (ownedKeys.contains(writer))
            throw new IllegalStateException("Identity key pair owns itself!");
        HashSet<PublicKeyHash> withCurrent = new HashSet<>(ancestors);
        withCurrent.add(writer);
        for (PublicKeyHash ownedKey : ownedKeys) {
            if (! withCurrent.contains(ownedKey))
                checkUserValidity(Integer.MAX_VALUE, owner, ownedKey, withCurrent, network);

        }
    }
}
