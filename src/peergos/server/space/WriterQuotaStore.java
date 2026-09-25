package peergos.server.space;

import peergos.shared.crypto.hash.*;

import java.util.*;

/** Space caps on writing spaces. A cap applies to the writer and every key it owns, directly or indirectly. */
public interface WriterQuotaStore {

    /** Set, or remove if the quota is empty, the cap on a writer.
     *
     * @return false if a request at least as new as this one has already been applied
     */
    boolean setWriterQuota(String username, PublicKeyHash writer, Optional<Long> quota, long utcMillis, byte[] signedRequest);

    /** Forget a writer's cap completely, for when its writing space is gone */
    void deleteWriterQuota(PublicKeyHash writer);

    Optional<Long> getWriterQuota(PublicKeyHash writer);

    Map<PublicKeyHash, Long> getWriterQuotas(String username);

    /**
     * @return the signed requests for every cap currently set by this user
     */
    List<byte[]> getSignedWriterQuotas(String username);

    /**
     * @return the keys that own this writer, directly or indirectly
     */
    List<PublicKeyHash> getAncestors(PublicKeyHash writer);

    /**
     * @return the bytes stored directly under this writer and every key it owns, directly or indirectly
     */
    long getSubtreeUsage(PublicKeyHash writer);
}
