package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;

public interface Committer {

    CompletableFuture<Snapshot> commit(PublicKeyHash owner,
                                       SigningPrivateKeyAndPublicHash signer,
                                       Optional<WriterData> wd,
                                       CommittedWriterData existing,
                                       TransactionId tid);

    default CompletableFuture<Snapshot> commit(PublicKeyHash owner,
                                               SigningPrivateKeyAndPublicHash signer,
                                               WriterData wd,
                                               CommittedWriterData existing,
                                               TransactionId tid) {
        return commit(owner, signer, Optional.of(wd), existing, tid);
    }
}
