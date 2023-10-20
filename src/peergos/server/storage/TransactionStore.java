package peergos.server.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;

import java.util.*;

public interface TransactionStore {

    TransactionId startTransaction(PublicKeyHash owner);

    void addBlock(Multihash hash, TransactionId tid, PublicKeyHash owner);

    void closeTransaction(PublicKeyHash owner, TransactionId tid);

    List<Multihash> getOpenTransactionBlocks();

    void clearOldTransactions(long cutoff);
}
