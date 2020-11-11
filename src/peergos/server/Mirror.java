package peergos.server;

import peergos.server.corenode.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.logging.*;

public class Mirror {

    public static void mirrorNode(Multihash nodeId,
                                  NetworkAccess mirror,
                                  JdbcIpnsAndSocial targetPointers,
                                  DeletableContentAddressedStorage targetStorage) {
        Logging.LOG().log(Level.INFO, "Mirroring data for node " + nodeId);
        List<String> allUsers = mirror.coreNode.getUsernames("").join();
        int userCount = 0;
        for (String username : allUsers) {
            List<UserPublicKeyLink> chain = mirror.coreNode.getChain(username).join();
            if (chain.get(chain.size() - 1).claim.storageProviders.contains(nodeId)) {
                try {
                    mirrorUser(username, mirror, targetPointers, targetStorage);
                    userCount++;
                } catch (Exception e) {
                    Logging.LOG().log(Level.WARNING, "Couldn't mirror user: " + username, e);
                }
            }
        }
        Logging.LOG().log(Level.INFO, "Finished mirroring data for node " + nodeId + ", with " + userCount + " users.");
    }

    public static void mirrorUser(String username,
                                  NetworkAccess source,
                                  JdbcIpnsAndSocial targetPointers,
                                  DeletableContentAddressedStorage targetStorage) {
        Logging.LOG().log(Level.INFO, "Mirroring data for " + username);
        Optional<PublicKeyHash> identity = source.coreNode.getPublicKeyHash(username).join();
        if (! identity.isPresent())
            return;
        Set<PublicKeyHash> ownedKeys = WriterData.getOwnedKeysRecursive(username, source.coreNode, source.mutable,
                source.dhtClient, source.hasher).join();
        for (PublicKeyHash ownedKey : ownedKeys) {
            mirrorMutableSubspace(identity.get(), ownedKey, source, targetPointers, targetStorage);
        }
        Logging.LOG().log(Level.INFO, "Finished mirroring data for " + username);
    }

    public static void mirrorMutableSubspace(PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             NetworkAccess source,
                                             JdbcIpnsAndSocial targetPointers,
                                             DeletableContentAddressedStorage targetStorage) {
        Optional<byte[]> updated = source.mutable.getPointer(owner, writer).join();
        if (! updated.isPresent()) {
            Logging.LOG().log(Level.WARNING, "Skipping unretrievable mutable pointer for: " + writer);
            return;
        }
        Optional<byte[]> existing = targetPointers.getPointer(writer).join();
        // First pin the new root, then commit updated pointer
        byte[] newPointer = updated.get();
        MaybeMultihash existingTarget = existing.isPresent() ?
                MutablePointers.parsePointerTarget(existing.get(), writer, source.dhtClient).join() :
                MaybeMultihash.empty();
        MaybeMultihash updatedTarget = MutablePointers.parsePointerTarget(newPointer, writer, source.dhtClient).join();
        // use a mirror call to distinguish from normal pin calls
        targetStorage.mirror(owner, existingTarget.toOptional(), updatedTarget.toOptional());
        targetPointers.setPointer(writer, existing, newPointer).join();
    }
}
