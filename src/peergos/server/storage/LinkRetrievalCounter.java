package peergos.server.storage;

import peergos.shared.crypto.hash.*;

public interface LinkRetrievalCounter {

    void increment(PublicKeyHash owner, long label);

    long getCount(PublicKeyHash owner, long label);
}
