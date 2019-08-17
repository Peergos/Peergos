package peergos.server;

import peergos.server.corenode.*;
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
                                  ContentAddressedStorage targetStorage) {
        Logging.LOG().log(Level.INFO, "Mirroring data for node " + nodeId);
        List<String> allUsers = mirror.coreNode.getUsernames("").join();
        for (String username : allUsers) {
            List<UserPublicKeyLink> chain = mirror.coreNode.getChain(username).join();
            if (chain.get(chain.size() - 1).claim.storageProviders.contains(nodeId))
                mirrorUser(username, mirror, targetPointers, targetStorage);
        }
    }

    public static void mirrorUser(String username,
                                  NetworkAccess source,
                                  JdbcIpnsAndSocial targetPointers,
                                  ContentAddressedStorage targetStorage) {
        Logging.LOG().log(Level.INFO, "Mirroring data for " + username);
        Optional<PublicKeyHash> identity = source.coreNode.getPublicKeyHash(username).join();
        if (! identity.isPresent())
            return;
        Set<PublicKeyHash> ownedKeys = WriterData.getOwnedKeysRecursive(username, source.coreNode, source.mutable, source.dhtClient).join();
        for (PublicKeyHash ownedKey : ownedKeys) {
            mirrorMutableSubspace(identity.get(), ownedKey, source, targetPointers, targetStorage);
        }
        Logging.LOG().log(Level.INFO, "Finished mirroring data for " + username);
    }

    public static void mirrorMutableSubspace(PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             NetworkAccess source,
                                             JdbcIpnsAndSocial targetPointers,
                                             ContentAddressedStorage targetStorage) {
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
        if (! updatedTarget.isPresent()) {
            // The writing key must have been deleted
            if (existingTarget.isPresent())
                targetStorage.recursiveUnpin(owner, existingTarget.get());
            return;
        }
        if (existingTarget.isPresent())
            targetStorage.pinUpdate(owner, existingTarget.get(), updatedTarget.get()).join();
        else
            targetStorage.recursivePin(owner, updatedTarget.get()).join();
        targetPointers.setPointer(writer, existing, newPointer).join();
    }
}
