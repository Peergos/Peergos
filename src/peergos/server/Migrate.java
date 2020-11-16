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

    public static boolean migrateToLocal(String username,
                                         List<UserPublicKeyLink> updatedChain,
                                         Multihash currentStorageNodeId,
                                         long localQuota,
                                         QuotaAdmin userQuotas,
                                         NetworkAccess network) {
        try {
            // Ensure user has enough local space quota
            if (userQuotas.getQuota(username) < localQuota)
                throw new IllegalStateException("Not enough space quota to migrate user!");

            network.coreNode.migrateUser(username, updatedChain, currentStorageNodeId).join();
            return true;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Privileged version of migrate that requires the user's keys, for running locally by the user
     *
     * @param user
     * @param localStorage
     * @param userQuotas
     * @param network
     * @return
     */
    public static boolean migrateToLocal(UserContext user,
                                         DeletableContentAddressedStorage localStorage,
                                         QuotaAdmin userQuotas,
                                         NetworkAccess network) {
        List<UserPublicKeyLink> existing = network.coreNode.getChain(user.username).join();
        List<UserPublicKeyLink> updatedChain = buildMigrationChain(existing, localStorage.id().join(), user.signer.secret);

        long quota = user.getQuota().join();
        Multihash currentStorageNodeId = existing.get(existing.size() - 1).claim.storageProviders.get(0);
        return migrateToLocal(user.username, updatedChain, currentStorageNodeId, quota, userQuotas, network);
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
