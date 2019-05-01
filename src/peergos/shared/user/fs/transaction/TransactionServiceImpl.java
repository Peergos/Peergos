package peergos.shared.user.fs.transaction;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.SafeRandom;
import peergos.shared.user.FileWrapperUpdater;
import peergos.shared.user.fs.*;
import peergos.shared.util.Futures;
import peergos.shared.util.ProgressConsumer;
import peergos.shared.util.Serialize;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class TransactionServiceImpl implements TransactionService {
    private static ProgressConsumer<Long> VOID_PROGRESS = l -> {};

    private final FileWrapperUpdater transactionDirUpdater;
    private final NetworkAccess networkAccess;
    private final Crypto crypto;

    public TransactionServiceImpl(NetworkAccess networkAccess,
                                  Crypto crypto,
                                  FileWrapperUpdater transactionDirUpdater) {
        this.transactionDirUpdater = transactionDirUpdater;
        this.networkAccess = networkAccess;
        this.crypto = crypto;
    }

    @Override
    public CompletableFuture<Boolean> open(Transaction transaction) {
        byte[] data = transaction.serialize();
        AsyncReader asyncReader = AsyncReader.build(data);
        return transactionDirUpdater.updated().thenCompose(dirWrapper ->
                dirWrapper.uploadOrOverwriteFile(transaction.name(), asyncReader, data.length, networkAccess,
                        crypto, VOID_PROGRESS,
                        dirWrapper.generateChildLocations(1, crypto.random))
                        .thenApply(e -> true));
    }

    @Override
    public CompletableFuture<Boolean> close(Transaction transaction) {
        return transactionDirUpdater.updated().thenCompose(dir ->
                dir.getChild(transaction.name(), networkAccess).thenApply(fileOpt -> {
                    boolean hasChild = fileOpt.isPresent();
                    if (!hasChild)
                        return CompletableFuture.completedFuture(false);
                    FileWrapper fileWrapper = fileOpt.get();
                    return dir.removeChild(fileWrapper, networkAccess, crypto.hasher);
                }).thenApply(e -> true));
    }

    @Override
    public CompletableFuture<Boolean> clear(Transaction transaction) {
        return transaction.clear(networkAccess);
    }

    private CompletableFuture<Transaction> read(FileWrapper fileWrapper) {
        FileProperties props = fileWrapper.getFileProperties();
        int size = (int) props.size;
        byte[] data = new byte[size];

        return fileWrapper.getInputStream(networkAccess, crypto.random, VOID_PROGRESS).thenApply(
                asyncReader -> Serialize.readFullArray(asyncReader, data)
        ).thenApply(done -> Transaction.deserialize(data));
    }

    @Override
    public CompletableFuture<Set<Transaction>> getOpenTransactions() {
        return transactionDirUpdater.updated()
                .thenCompose(dirWrapper -> dirWrapper.getChildren(networkAccess)
                        .thenCompose(children -> {
                            List<CompletableFuture<Transaction>> collect = children.stream().map(this::read).collect(Collectors.toList());
                            return Futures.combineAll(collect);
                        })
                );
    }

}
