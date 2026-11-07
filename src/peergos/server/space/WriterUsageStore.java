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

        /** An edge to this writer was added or removed on its own, ahead of or in place of its parent's update */
        default void edgeChanged(PublicKeyHash owned) {
            usageChanged(owned, 0, true);
        }
    }

    /** The listener is called after each successful updateWriterUsageAtomically, each change to a writer quota, each
     *  writer removed, and (edgeChanged) each edge added or removed outside those */
    void addUsageListener(UsageListener listener);

    void addWriter(String owner, PublicKeyHash writer);

    /** Record that a registered writer owns another, ahead of the queue's processing of the parent's pointer update
     *  that names it (or of that update itself, for a commit creating the child), so that the caps over the parent
     *  bound the child's first writes. Does nothing if the edge is already there.
     *
     * @return whether the edge is new
     */
    boolean addOwnedKey(PublicKeyHash parent, PublicKeyHash owned);

    /** Take back an edge recorded ahead of a commit that was then refused */
    void removeOwnedKey(PublicKeyHash parent, PublicKeyHash owned);

    /** Forget a writer that nothing owns any more, so it can't write again without proving it is owned. Its
     *  provisional rows go with it. */
    void removeWriter(PublicKeyHash writer);

    /** A writer a commit is registering under a parent, until the commit lands or is refused. It is in the store, not
     *  in the memory of the server handling the commit, so another server, or that one after a restart, finds it, and
     *  takes the registration back once it expires. Until then a new writer may set only the pointer update its
     *  commit carries, if any.
     *
     * @param newWriter whether the commit registered the writer itself, not only its edge from the parent
     * @param signed the pointer update the commit carries for the writer
     */
    record ProvisionalWriter(PublicKeyHash writer, PublicKeyHash parent, PublicKeyHash owner, boolean newWriter,
                             Optional<byte[]> signed, long expires) {}

    /** Records a provisional writer, unless one is recorded for this writer and parent already
     *
     * @return whether it is new
     */
    boolean addProvisional(ProvisionalWriter provisional);

    List<ProvisionalWriter> getProvisional(PublicKeyHash writer);

    List<ProvisionalWriter> getExpiredProvisional(long now);

    void removeProvisional(PublicKeyHash writer, PublicKeyHash parent);

    Set<PublicKeyHash> getAllWriters();

    Set<PublicKeyHash> getAllWriters(PublicKeyHash owner);

    Set<PublicKeyHash> getAllWriters(String owner);

    WriterUsage getUsage(PublicKeyHash writer);

    PublicKeyHash getOwnerKey(PublicKeyHash writer);

    /**
     * @return the keys that directly own this writer
     */
    Set<PublicKeyHash> getParents(PublicKeyHash writer);

    /**
     * @return the keys this writer has an edge to
     */
    Set<PublicKeyHash> getOwnedKeys(PublicKeyHash writer);

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
