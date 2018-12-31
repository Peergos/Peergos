package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
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
        if (! fileAccess.isDirectory()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Transaction.run(capability.owner,
                    tid -> network.tree.remove(capability.owner, signer, capability.getMapKey(), fileAccess.committedHash(), tid).thenAccept(removed -> {
                        // remove from parent
                        if (parentRetrievedCapability != null)
                            ((DirAccess) parentRetrievedCapability.fileAccess)
                                    .removeChild(this, parentRetrievedCapability.capability.toWritable(signer), network);
                        result.complete(true);
                    }), network.dhtClient);
            return result;
        }
        return ((DirAccess) fileAccess).getChildren(network, capability).thenCompose(files -> {
            for (RetrievedCapability file : files)
                file.remove(network, null, signer);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Transaction.run(capability.owner,
                    tid -> network.tree.remove(capability.owner, signer, capability.getMapKey(), fileAccess.committedHash(), tid).thenAccept(removed -> {
                        // remove from parent
                        if (parentRetrievedCapability != null)
                            ((DirAccess) parentRetrievedCapability.fileAccess)
                                    .removeChild(this, parentRetrievedCapability.capability.toWritable(signer), network);
                        result.complete(removed);
                    }), network.dhtClient);
            return result;
        });
    }

    public RetrievedCapability withCryptree(CryptreeNode fileAccess) {
        return new RetrievedCapability(capability, fileAccess);
    }

    public RetrievedCapability withWriter(Optional<SecretSigningKey> writer) {
        AbsoluteCapability cap = new AbsoluteCapability(capability.owner, capability.writer, capability.getMapKey(), capability.baseKey, writer);
        return new RetrievedCapability(cap, this.fileAccess);
    }
}
