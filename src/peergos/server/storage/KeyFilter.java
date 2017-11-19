package peergos.server.storage;

import peergos.shared.crypto.hash.*;

public interface KeyFilter {

    boolean isAllowed(PublicKeyHash signerHash);
}
