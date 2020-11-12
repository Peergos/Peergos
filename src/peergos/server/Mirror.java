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
                                  CoreNode core,
                                  MutablePointers p2pPointers,
                                  ContentAddressedStorage storage,
                                  JdbcIpnsAndSocial targetPointers,
                                  TransactionStore transactions,
                                  DeletableContentAddressedStorage targetStorage,
                                  Hasher hasher) {
        Logging.LOG().log(Level.INFO, "Mirroring data for node " + nodeId);
        List<String> allUsers = core.getUsernames("").join();
        int userCount = 0;
        for (String username : allUsers) {
            List<UserPublicKeyLink> chain = core.getChain(username).join();
            if (chain.get(chain.size() - 1).claim.storageProviders.contains(nodeId)) {
                try {
                    mirrorUser(username, core, p2pPointers, storage, targetPointers,
                            transactions, targetStorage, hasher);
                    userCount++;
                } catch (Exception e) {
                    Logging.LOG().log(Level.WARNING, "Couldn't mirror user: " + username, e);
                }
            }
        }
        Logging.LOG().log(Level.INFO, "Finished mirroring data for node " + nodeId + ", with " + userCount + " users.");
    }

    /**
     *
     * @param username
     * @param core
     * @param p2pPointers
     * @param storage
     * @param targetPointers
     * @param transactions
     * @param targetStorage
     * @param hasher
     * @return The version mirrored
     */
    public static Map<PublicKeyHash, byte[]> mirrorUser(String username,
                                                        CoreNode core,
                                                        MutablePointers p2pPointers,
                                                        ContentAddressedStorage storage,
                                                        JdbcIpnsAndSocial targetPointers,
                                                        TransactionStore transactions,
                                                        DeletableContentAddressedStorage targetStorage,
                                                        Hasher hasher) {
        Logging.LOG().log(Level.INFO, "Mirroring data for " + username);
        Optional<PublicKeyHash> identity = core.getPublicKeyHash(username).join();
        if (! identity.isPresent())
            return Collections.emptyMap();
        Map<PublicKeyHash, byte[]> versions = new HashMap<>();
        Set<PublicKeyHash> ownedKeys = WriterData.getOwnedKeysRecursive(username, core, p2pPointers,
                storage, hasher).join();
        for (PublicKeyHash ownedKey : ownedKeys) {
            Optional<byte[]> version = mirrorMutableSubspace(identity.get(), ownedKey, p2pPointers, storage,
                    targetPointers, transactions, targetStorage);
            if (version.isPresent())
                versions.put(ownedKey, version.get());
        }
        Logging.LOG().log(Level.INFO, "Finished mirroring data for " + username);
        return versions;
    }

    /**
     *
     * @param owner
     * @param writer
     * @param p2pPointers
     * @param storage
     * @param targetPointers
     * @param targetStorage
     * @return the version mirrored
     */
    public static Optional<byte[]> mirrorMutableSubspace(PublicKeyHash owner,
                                                         PublicKeyHash writer,
                                                         MutablePointers p2pPointers,
                                                         ContentAddressedStorage storage,
                                                         JdbcIpnsAndSocial targetPointers,
                                                         TransactionStore transactions,
                                                         DeletableContentAddressedStorage targetStorage) {
        Optional<byte[]> updated = p2pPointers.getPointer(owner, writer).join();
        if (! updated.isPresent()) {
            Logging.LOG().log(Level.WARNING, "Skipping unretrievable mutable pointer for: " + writer);
            return updated;
        }
        Optional<byte[]> existing = targetPointers.getPointer(writer).join();
        // First pin the new root, then commit updated pointer
        byte[] newPointer = updated.get();
        MaybeMultihash existingTarget = existing.isPresent() ?
                MutablePointers.parsePointerTarget(existing.get(), writer, storage).join() :
                MaybeMultihash.empty();
        MaybeMultihash updatedTarget = MutablePointers.parsePointerTarget(newPointer, writer, storage).join();
        // use a mirror call to distinguish from normal pin calls
        TransactionId tid = transactions.startTransaction(owner);
        try {
            targetStorage.mirror(owner, existingTarget.toOptional(), updatedTarget.toOptional(), tid);
            targetPointers.setPointer(writer, existing, newPointer).join();
        } finally {
            transactions.closeTransaction(owner, tid);
        }
        return updated;
    }
}
