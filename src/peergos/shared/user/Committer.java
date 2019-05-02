package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;

import java.util.concurrent.*;

public interface Committer {

    CompletableFuture<Snapshot> commit(PublicKeyHash owner,
                                       SigningPrivateKeyAndPublicHash signer,
                                       WriterData wd,
                                       CommittedWriterData existing,
                                       TransactionId tid);
}
