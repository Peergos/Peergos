package peergos.server.space;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.*;

import java.util.*;

public interface WriterUsageStore {

    interface UsageListener {
        void usageChanged(PublicKeyHash writer, long delta, boolean ownedKeysChanged);

        default void writerQuotaChanged(PublicKeyHash writer, Optional<Long> quota) {}
    }

    /** The listener is called after each successful updateWriterUsageAtomically, and each change to a writer quota */
    void addUsageListener(UsageListener listener);

    void addWriter(String owner, PublicKeyHash writer);

    Set<PublicKeyHash> getAllWriters();

    Set<PublicKeyHash> getAllWriters(PublicKeyHash owner);

    Set<PublicKeyHash> getAllWriters(String owner);

    WriterUsage getUsage(PublicKeyHash writer);

    PublicKeyHash getOwnerKey(PublicKeyHash writer);

    /**
     * @return the keys that directly own this writer
     */
    Set<PublicKeyHash> getParents(PublicKeyHash writer);

    PublicKeyHash getOwnerKey(String username);

    String getOwner(PublicKeyHash writer);

    /**
     * Atomically update writer size/target AND user total_bytes in a single SQL statement,
     * using a CAS on the writer's current target to prevent double-counting under concurrent access.
     *
     * @return true if the CAS succeeded (update applied), false if the target had already changed
     */
    boolean updateWriterUsageAtomically(PublicKeyHash writer,
                                        MaybeMultihash oldTarget,
                                        MaybeMultihash newTarget,
                                        Set<PublicKeyHash> removedOwnedKeys,
                                        Set<PublicKeyHash> addedOwnedKeys,
                                        long newDirectSize,
                                        long delta,
                                        boolean errored);

    // return current usage root, and username
    List<Triple<Multihash, String, PublicKeyHash>> getAllTargets();

    // return current usage root, and username
    List<Triple<Multihash, String, PublicKeyHash>> getAllTargets(String username);

    /**
     *
     * @return All usernames and owner keys using space locally
     */
    List<Pair<String, PublicKeyHash>> getAllOwners();
}
