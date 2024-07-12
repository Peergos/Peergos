package peergos.server;

import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.Multihash;

import java.util.*;
import java.util.stream.*;

public class Migrate {

    public static List<UserPublicKeyLink> buildMigrationChain(List<UserPublicKeyLink> existing,
                                                              Multihash newStorageId,
                                                              SecretSigningKey signer) {
        UserPublicKeyLink last = existing.get(existing.size() - 1);
        UserPublicKeyLink.Claim newClaim = UserPublicKeyLink.Claim.build(last.claim.username, signer,
                last.claim.expiry.plusDays(1), Arrays.asList(newStorageId)).join();
        UserPublicKeyLink updatedLast = last.withClaim(newClaim);
        return Stream.concat(
                existing.stream().limit(existing.size() - 1),
                Stream.of(updatedLast))
                .collect(Collectors.toList());
    }
}
