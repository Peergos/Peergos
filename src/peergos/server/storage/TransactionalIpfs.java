package peergos.server.storage;

import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.stream.*;

public class TransactionalIpfs extends DelegatingStorage implements DeletableContentAddressedStorage {

    private final TransactionStore transactions;
    private final DeletableContentAddressedStorage target;

    public TransactionalIpfs(DeletableContentAddressedStorage target, TransactionStore transactions) {
        super(target);
        this.target = target;
        this.transactions = transactions;
    }

    @Override
    public Stream<Multihash> getAllFiles() {
        return target.getAllFiles();
    }

    @Override
    public void delete(Multihash hash) {
        target.delete(hash);
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        return transactions.getOpenTransactionBlocks();
    }
}
