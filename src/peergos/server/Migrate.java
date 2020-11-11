package peergos.server;

import peergos.server.corenode.*;
import peergos.server.storage.*;
import peergos.server.storage.admin.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;
import peergos.shared.user.*;

import java.time.*;
import java.util.*;
import java.util.stream.*;

public class Migrate {

    public static boolean migrateToLocal(String username,
                                         List<UserPublicKeyLink> updatedChain,
                                         long localQuota,
                                         List<BlindFollowRequest> pending,
                                         TransactionStore transactions,
                                         DeletableContentAddressedStorage localStorage,
                                         JdbcIpnsAndSocial rawPointers,
                                         JdbcIpnsAndSocial rawSocial,
                                         QuotaAdmin userQuotas,
                                         Crypto crypto,
                                         NetworkAccess network) {
        try {
            PublicKeyHash owner = updatedChain.get(updatedChain.size() - 1).owner;
            // Ensure user has enough local space quota
            if (userQuotas.getQuota(username) < localQuota)
                throw new IllegalStateException("Not enough space quota to migrate user!");

            // Mirror all the data to local
            Mirror.mirrorUser(username, network, rawPointers, transactions, localStorage);
            Map<PublicKeyHash, byte[]> userSnapshot = Mirror.mirrorUser(username, network, rawPointers, transactions, localStorage);

            // Copy pending follow requests to local server
            for (BlindFollowRequest req : pending) {
                // write directly to local social database to avoid being redirected to user's current node
                rawSocial.addFollowRequest(owner, req.serialize()).join();
            }

            // Update pki data to announce this node as user's storage node
            // This will signal the world, including the previous storage node to redirect writes here
            // and start accepting writes (and follow requests) on this server for this user
            byte[] data = new CborObject.CborList(updatedChain).serialize();
            ProofOfWork work = crypto.hasher.generateProofOfWork(ProofOfWork.MIN_DIFFICULTY, data).join();
            Optional<RequiredDifficulty> retry = network.coreNode.updateChain(username, updatedChain, work).join();
            if (retry.isPresent())
                throw new IllegalStateException("Unable to update storage node in PKI during migration!");

            // Enforce redirecting writes from old server to this one and commit any diff since mirroring

            // todo send snapshot to previous storage node and commit diff returned

            return true;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Privileged version of migrate that requires the user's keys, for running locally by the user
     *
     * @param user
     * @param localStorage
     * @param rawPointers
     * @param rawSocial
     * @param userQuotas
     * @param crypto
     * @param network
     * @return
     */
    public static boolean migrateToLocal(UserContext user,
                                         TransactionStore transactions,
                                         DeletableContentAddressedStorage localStorage,
                                         JdbcIpnsAndSocial rawPointers,
                                         JdbcIpnsAndSocial rawSocial,
                                         QuotaAdmin userQuotas,
                                         Crypto crypto,
                                         NetworkAccess network) {
        List<UserPublicKeyLink> existing = network.coreNode.getChain(user.username).join();
        UserPublicKeyLink last = existing.get(existing.size() - 1);
        UserPublicKeyLink.Claim newClaim = UserPublicKeyLink.Claim.build(user.username, user.signer.secret,
                LocalDate.now().plusMonths(2), Arrays.asList(localStorage.id().join()));
        UserPublicKeyLink updatedLast = last.withClaim(newClaim);
        List<UserPublicKeyLink> updatedChain = Stream.concat(
                existing.stream().limit(existing.size() - 1),
                Stream.of(updatedLast))
                .collect(Collectors.toList());

        long quota = user.getQuota().join();
        List<BlindFollowRequest> pending = user.getFollowRequests().join();
        return migrateToLocal(user.username, updatedChain, quota, pending, transactions, localStorage, rawPointers,
                rawSocial, userQuotas, crypto, network);
    }
}
