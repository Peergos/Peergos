package peergos.shared.user.fs.transaction;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.SafeRandom;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.Futures;
import peergos.shared.util.ProgressConsumer;
import peergos.shared.util.Serialize;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class TransactionServiceImpl implements TransactionService {
    private static ProgressConsumer<Long> VOID_PROGRESS = l -> {};

    private final FileWrapperUpdater transactionDirUpdater;
    private final SigningPrivateKeyAndPublicHash signer;
    private final NetworkAccess networkAccess;
    private final Crypto crypto;

    public TransactionServiceImpl(NetworkAccess networkAccess,
                                  Crypto crypto,
                                  FileWrapper transactionsDir) {
        this.transactionDirUpdater = v -> transactionsDir.getUpdated(v, networkAccess);
        this.signer = transactionsDir.signingPair();
        this.networkAccess = networkAccess;
        this.crypto = crypto;
    }

    @Override
    public SigningPrivateKeyAndPublicHash getSigner() {
        return signer;
    }

    @Override
    public CompletableFuture<Snapshot> open(Snapshot version, Committer committer, Transaction transaction) {
        byte[] data = transaction.serialize();
        AsyncReader asyncReader = AsyncReader.build(data);
        return transactionDirUpdater.updated(version).thenCompose(dir ->
                dir.uploadFileSection(version, committer, transaction.name(), asyncReader, false,
                        0, data.length, Optional.empty(), false, networkAccess,
                        crypto, VOID_PROGRESS, dir.generateChildLocations(1, crypto.random)));
    }

    @Override
    public CompletableFuture<Snapshot> close(Snapshot version, Committer committer, Transaction transaction) {
        return transactionDirUpdater.updated(version).thenCompose(dir ->
                dir.getChild(transaction.name(), networkAccess).thenCompose(fileOpt -> {
                    boolean hasChild = fileOpt.isPresent();
                    if (!hasChild)
                        return CompletableFuture.completedFuture(version);
                    FileWrapper fileWrapper = fileOpt.get();
                    return dir.removeChild(version, committer, fileWrapper, networkAccess, crypto.hasher);
                }));
    }

    @Override
    public CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, Transaction transaction) {
        return transaction.clear(version, committer, networkAccess);
    }

    private CompletableFuture<Transaction> read(Snapshot version, FileWrapper txnFile) {
        FileProperties props = txnFile.getFileProperties();
        int size = (int) props.size;
        byte[] data = new byte[size];

        CommittedWriterData cwd = version.get(txnFile.writer());
        return txnFile.getInputStream(cwd.props, networkAccess, crypto.random, VOID_PROGRESS)
                .thenApply(reader -> Serialize.readFullArray(reader, data))
                .thenApply(done -> Transaction.deserialize(data));
    }

    @Override
    public CompletableFuture<Set<Transaction>> getOpenTransactions(Snapshot version) {
        return transactionDirUpdater.updated(version)
                .thenCompose(dir -> dir.getChildren(networkAccess)
                        .thenCompose(children -> {
                            List<CompletableFuture<Transaction>> collect = children.stream()
                                    .map(c -> read(version, c))
                                    .collect(Collectors.toList());
                            return Futures.combineAll(collect);
                        })
                );
    }
}
