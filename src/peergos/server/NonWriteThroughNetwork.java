package peergos.server;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.server.social.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;

import java.util.*;

public class NonWriteThroughNetwork extends NetworkAccess {

    protected NonWriteThroughNetwork(CoreNode coreNode,
                                     SocialNetwork social,
                                     ContentAddressedStorage ipfs,
                                     MutablePointers mutable,
                                     MutableTree tree,
                                     WriteSynchronizer synchronizer,
                                     InstanceAdmin instanceAdmin,
                                     SpaceUsage spaceUsage,
                                     List<String> usernames,
                                     boolean isJavascript) {
        super(coreNode, social, ipfs, mutable, tree, synchronizer, instanceAdmin, spaceUsage, null, usernames, isJavascript);
    }

    public static NetworkAccess build(NetworkAccess source) {
        ContentAddressedStorage nonWriteThroughIpfs = new NonWriteThroughStorage(source.dhtClient);
        MutablePointers nonWriteThroughPointers = new NonWriteThroughMutablePointers(source.mutable, nonWriteThroughIpfs);
        NonWriteThroughCoreNode nonWriteThroughCoreNode = new NonWriteThroughCoreNode(source.coreNode, nonWriteThroughIpfs);
        NonWriteThroughSocialNetwork nonWriteThroughSocial = new NonWriteThroughSocialNetwork(source.social, nonWriteThroughIpfs);
        WriteSynchronizer synchronizer = new WriteSynchronizer(nonWriteThroughPointers, nonWriteThroughIpfs, source.hasher);
        MutableTree nonWriteThroughTree = new MutableTreeImpl(nonWriteThroughPointers, nonWriteThroughIpfs, source.hasher, synchronizer);
        return new NonWriteThroughNetwork(nonWriteThroughCoreNode,
                nonWriteThroughSocial,
                nonWriteThroughIpfs,
                nonWriteThroughPointers,
                nonWriteThroughTree, synchronizer, source.instanceAdmin, source.spaceUsage, source.usernames, false);
    }
}
