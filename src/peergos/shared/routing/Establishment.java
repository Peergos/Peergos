package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;

/** A record that establishes a key and commits to its successor: an inception or a rotation. */
public interface Establishment {

    PublicSigningKey key();

    byte[] next();
}
