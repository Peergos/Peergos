package peergos.server.storage.admin;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.util.*;

/** The api for administrators approving pending space requests
 *
 */
public interface QuotaAdmin extends QuotaControl {

    boolean acceptingSignups();

    List<SpaceUsage.LabelledSignedSpaceRequest> getSpaceRequests();

    void approveSpaceRequest(PublicKeyHash adminIdentity, Multihash instanceIdentity, byte[] signedRequest);

    void removeSpaceRequest(String username, byte[] unsigned);
}
