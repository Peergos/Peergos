package peergos.server;

import peergos.server.storage.*;
import peergos.server.storage.admin.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;

import java.time.*;
import java.util.*;
import java.util.stream.*;

public class Migrate {

    /** Privileged version of migrate that requires the user's keys, for running locally by the user
     *
     * @param user
     * @param localStorage
     * @param network
     * @return
     */
    public static boolean migrateToLocal(UserContext user,
                                         DeletableContentAddressedStorage localStorage,
                                         NetworkAccess network) {
        List<UserPublicKeyLink> existing = network.coreNode.getChain(user.username).join();
        List<UserPublicKeyLink> updatedChain = buildMigrationChain(existing, localStorage.id().join(), user.signer.secret);

        Multihash currentStorageNodeId = existing.get(existing.size() - 1).claim.storageProviders.get(0);
        network.coreNode.migrateUser(user.username, updatedChain, currentStorageNodeId).join();
        return true;
    }

    public static List<UserPublicKeyLink> buildMigrationChain(List<UserPublicKeyLink> existing,
                                                              Multihash newStorageId,
                                                              SecretSigningKey signer) {
        UserPublicKeyLink last = existing.get(existing.size() - 1);
        UserPublicKeyLink.Claim newClaim = UserPublicKeyLink.Claim.build(last.claim.username, signer,
                LocalDate.now().plusMonths(2), Arrays.asList(newStorageId));
        UserPublicKeyLink updatedLast = last.withClaim(newClaim);
        return Stream.concat(
                existing.stream().limit(existing.size() - 1),
                Stream.of(updatedLast))
                .collect(Collectors.toList());
    }
}
