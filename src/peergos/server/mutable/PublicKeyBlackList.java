package peergos.server.mutable;

import peergos.shared.crypto.hash.*;

public interface PublicKeyBlackList {

    boolean isAllowed(PublicKeyHash keyHash);
}
