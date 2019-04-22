package peergos.shared.user;

import peergos.shared.storage.*;

import java.util.concurrent.*;

public interface Mutation {

    CompletableFuture<WriterData> apply(WriterData input, TransactionId tid);
}
