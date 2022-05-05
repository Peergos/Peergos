package peergos.server.tests.util;

import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.transaction.*;
import peergos.shared.util.*;

import java.util.concurrent.*;

public class NonClosingTransactionService extends TransactionServiceImpl {

    public NonClosingTransactionService(NetworkAccess network,
                                        Crypto crypto,
                                        FileWrapper transactionsDir) {
        super(network, crypto, transactionsDir);
    }

    @Override
    public CompletableFuture<Snapshot> close(Snapshot version, Committer committer, Transaction transaction) {
        return Futures.of(version);
    }
}
