package peergos.server.storage.admin;

import peergos.shared.cbor.*;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.controller.*;
import peergos.shared.util.*;

import java.time.LocalDateTime;
import java.util.*;

/** The api for administrators approving pending space requests
 *
 */
public interface QuotaAdmin extends QuotaControl {

    AllowedSignups acceptingSignups();

    boolean allowSignupOrUpdate(String username, String token);

    PaymentProperties createPaidUser(String username);

    void removeDesiredQuota(String username);

    boolean addToken(String token);

    boolean consumeToken(String username, String token);

    default void setPki(CoreNode core) {}

    default String generateToken(SafeRandom rnd) {
        String token = ArrayOps.bytesToHex(rnd.randomBytes(32));
        addToken(token);
        return token;
    }

    long getQuota(String username);

    void removeQuota(String username);

    boolean hadQuota(String username, LocalDateTime time);

    List<String> getLocalUsernames();

    List<SpaceUsage.LabelledSignedSpaceRequest> getSpaceRequests();

    void approveSpaceRequest(PublicKeyHash adminIdentity, Multihash instanceIdentity, byte[] signedRequest);

    void removeSpaceRequest(String username, byte[] unsigned);

    static QuotaControl.SpaceRequest parseQuotaRequest(PublicKeyHash owner, byte[] signedRequest, ContentAddressedStorage dht) {
        // check request is valid
        Optional<PublicSigningKey> ownerOpt = dht.getSigningKey(owner, owner).join();
        if (!ownerOpt.isPresent())
            throw new IllegalStateException("Couldn't retrieve owner key!");
        byte[] raw = ownerOpt.get().unsignMessage(signedRequest).join();
        CborObject cbor = CborObject.fromByteArray(raw);
        QuotaControl.SpaceRequest req = QuotaControl.SpaceRequest.fromCbor(cbor);
        long now = System.currentTimeMillis();
        if (req.utcMillis < now - 300_000)
            throw new IllegalStateException("Stale auth time in space request! " + req.utcMillis + " !< " + now + " - 30000");
        return req;
    }
}
