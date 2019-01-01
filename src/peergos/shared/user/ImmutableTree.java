package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.io.*;
import java.util.concurrent.*;

/**
 * A content-addressed version of a Map&lt;byte[], Multihash&gt;
 */
public interface ImmutableTree {

    /**
     *
     * @param rawKey
     * @return value stored under rawKey
     * @throws IOException
     */
    CompletableFuture<MaybeMultihash> get(byte[] rawKey);

    /**
     *
     * @param rawKey
     * @param value
     * @return hash of new tree root
     * @throws IOException
     */
    CompletableFuture<Multihash> put(PublicKeyHash owner,
                                     SigningPrivateKeyAndPublicHash writer,
                                     byte[] rawKey,
                                     MaybeMultihash existing,
                                     Multihash value,
                                     TransactionId tid);

    /**
     *
     * @param rawKey
     * @return hash of new tree root
     * @throws IOException
     */
    CompletableFuture<Multihash> remove(PublicKeyHash owner,
                                        SigningPrivateKeyAndPublicHash writer,
                                        byte[] rawKey,
                                        MaybeMultihash existing,
                                        TransactionId tid);
}
