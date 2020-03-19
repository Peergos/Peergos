package peergos.server.storage.admin;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.util.*;

/** The api for administrators approving pending space requests
 *
 */
public interface QuotaAdmin extends QuotaControl {

    boolean acceptingSignups();

    boolean allowSignupOrUpdate(String username);

    long getQuota(String username);

    List<String> getLocalUsernames();

    List<SpaceUsage.LabelledSignedSpaceRequest> getSpaceRequests();

    void approveSpaceRequest(PublicKeyHash adminIdentity, Multihash instanceIdentity, byte[] signedRequest);

    void removeSpaceRequest(String username, byte[] unsigned);

    static QuotaControl.SpaceRequest parseQuotaRequest(PublicKeyHash owner, byte[] signedRequest, ContentAddressedStorage dht) {
        // check request is valid
        Optional<PublicSigningKey> ownerOpt = dht.getSigningKey(owner).join();
        if (!ownerOpt.isPresent())
            throw new IllegalStateException("Couldn't retrieve owner key!");
        byte[] raw = ownerOpt.get().unsignMessage(signedRequest);
        CborObject cbor = CborObject.fromByteArray(raw);
        QuotaControl.SpaceRequest req = QuotaControl.SpaceRequest.fromCbor(cbor);
        if (req.utcMillis < System.currentTimeMillis() - 30_000)
            throw new IllegalStateException("Stale auth time in space request!");
        return req;
    }
}
