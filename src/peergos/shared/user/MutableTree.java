package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.MaybeMultihash;
import peergos.shared.storage.*;

import java.io.*;
import java.util.concurrent.*;

public interface MutableTree {

    /**
     *
     * @param base
     * @param owner
     * @param sharingKey
     * @param mapKey
     * @param value
     * @return the new root WriterData
     * @throws IOException
     */
    CompletableFuture<WriterData> put(WriterData base,
                                      PublicKeyHash owner,
                                      SigningPrivateKeyAndPublicHash sharingKey,
                                      byte[] mapKey,
                                      MaybeMultihash existing,
                                      Multihash value,
                                      TransactionId tid);

    /**
     *
     * @param base The WriterData at the current mutable pointer for the writer
     * @param owner
     * @param writer
     * @param mapKey
     * @return  the value stored under mapKey for sharingKey
     * @throws IOException
     */
    CompletableFuture<MaybeMultihash> get(WriterData base, PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey);

    /**
     *
     * @param owner
     * @param sharingKey
     * @param mapKey
     * @return  hash(sharingKey.metadata) | the new root hash of the tree
     * @throws IOException
     */
    CompletableFuture<WriterData> remove(WriterData base,
                                         PublicKeyHash owner,
                                         SigningPrivateKeyAndPublicHash sharingKey,
                                         byte[] mapKey,
                                         MaybeMultihash existing,
                                         TransactionId tid);

}
