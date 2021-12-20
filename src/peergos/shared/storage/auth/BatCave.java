package peergos.shared.storage.auth;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** This is used to store a mirror bat (or two during rotations) for each user.
 *
 */
public interface BatCave {

    Optional<Bat> getBat(BatId id);

    CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth);

    CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth);

    default CompletableFuture<List<BatWithId>> getUserBats(String username, SigningPrivateKeyAndPublicHash identity) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.BATS_URL + "getUserBats", System.currentTimeMillis());
        byte[] auth = req.sign(identity.secret);
        return getUserBats(username, auth);
    }

    default CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, SigningPrivateKeyAndPublicHash identity) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.BATS_URL + "addBat", System.currentTimeMillis());
        byte[] auth = req.sign(identity.secret);
        return addBat(username, id, bat, auth);
    }

    class HTTP implements BatCave {
        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public Optional<Bat> getBat(BatId id) {
            throw new IllegalStateException("Cannot be called over http!");
        }

        @Override
        public CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth) {
            return poster.get(Constants.BATS_URL + "getUserBats?username=" + username + "&auth=" + ArrayOps.bytesToHex(auth))
                    .thenApply(res ->
                            ((CborObject.CborList)CborObject.fromByteArray(res)).value
                                    .stream()
                                    .map(BatWithId::fromCbor)
                                    .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth) {
            return poster.get(Constants.BATS_URL + "addBat?username=" + username
                    + "&batid=" + id.id
                    + "&bat=" + bat.encodeSecret()
                    + "&auth=" + ArrayOps.bytesToHex(auth))
                    .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value);
        }
    }
}
