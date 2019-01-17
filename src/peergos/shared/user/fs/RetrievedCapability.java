package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.storage.*;
import peergos.shared.user.fs.cryptree.*;

import java.util.*;
import java.util.concurrent.*;

public class RetrievedCapability {
    public final AbsoluteCapability capability;
    public final CryptreeNode fileAccess;

    public RetrievedCapability(AbsoluteCapability capability, CryptreeNode fileAccess) {
        if (fileAccess == null)
            throw new IllegalStateException("Null FileAccess!");
        this.capability = capability;
        this.fileAccess = fileAccess;
    }

    public boolean equals(Object that) {
        if (that == null)
            return false;
        if (!(that instanceof RetrievedCapability))
            return false;
        return capability.equals(((RetrievedCapability)that).capability);
    }

    public CompletableFuture<Boolean> remove(NetworkAccess network,
                                             RetrievedCapability parentRetrievedCapability,
                                             SigningPrivateKeyAndPublicHash signer) {
        if (! capability.isWritable())
            return CompletableFuture.completedFuture(false);
        WritableAbsoluteCapability parentCap = parentRetrievedCapability != null ?
                (WritableAbsoluteCapability) parentRetrievedCapability.capability :
                null;
        if (! fileAccess.isDirectory()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Transaction.call(this.capability.owner,
                    tid -> network.tree.remove(this.capability.owner, signer, this.capability.getMapKey(), fileAccess.committedHash(), tid).thenAccept(removed -> {
                        // remove from parent
                        if (parentCap != null)
                            ((DirAccess) parentRetrievedCapability.fileAccess)
                                    .removeChild(this, parentCap, Optional.of(signer), network);
                        result.complete(true);
                    }), network.dhtClient);
            return result;
        }
        return ((DirAccess) fileAccess).getChildren(network, this.capability).thenCompose(files -> {
            for (RetrievedCapability file : files)
                file.remove(network, null, signer);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Transaction.call(this.capability.owner,
                    tid -> network.tree.remove(this.capability.owner, signer, this.capability.getMapKey(), fileAccess.committedHash(), tid).thenAccept(removed -> {
                        // remove from parent
                        if (parentCap != null)
                            ((DirAccess) parentRetrievedCapability.fileAccess)
                                    .removeChild(this, parentCap, Optional.of(signer), network);
                        result.complete(removed);
                    }), network.dhtClient);
            return result;
        });
    }

    public RetrievedCapability withCryptree(CryptreeNode fileAccess) {
        return new RetrievedCapability(capability, fileAccess);
    }
}
