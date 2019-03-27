package peergos.server;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.server.social.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.user.*;

public class NonWriteThroughNetwork {

    public static NetworkAccess build(NetworkAccess source) {
        NonWriteThroughStorage storage = new NonWriteThroughStorage(source.dhtClient);
        NonWriteThroughMutablePointers mutable = new NonWriteThroughMutablePointers(source.mutable, storage);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, storage);
        MutableTree mutableTree = new MutableTreeImpl(mutable, storage, synchronizer);
        return new NetworkAccess(
                new NonWriteThroughCoreNode(source.coreNode, storage),
                new NonWriteThroughSocialNetwork(source.social, storage),
                storage,
                mutable,
                mutableTree,
                synchronizer,
                source.instanceAdmin,
                source.usernames,
                false);
    }
}
