package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;

/** A record that only the current key's latest one counts: a move or a server's addresses. */
public interface Update {

    PublicSigningKey key();

    long counter();
}
