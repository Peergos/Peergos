package peergos.server.storage.auth;

import peergos.shared.io.ipfs.cid.*;

import java.util.concurrent.*;

public interface BlockRequestAuthoriser {

    CompletableFuture<Boolean> allowRead(Cid block, Cid sourceNodeId, String auth);
}
