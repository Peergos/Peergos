package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.fs.cryptree.*;

import java.util.*;
import java.util.concurrent.*;

public class RetrievedCapability {
    public final Capability capability;
    public final CryptreeNode fileAccess;

    public RetrievedCapability(Capability capability, CryptreeNode fileAccess) {
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
            network.tree.remove(this.capability.location.owner, signer, this.capability.location.getMapKey(), fileAccess.committedHash()).thenAccept(removed -> {
                // remove from parent
                if (parentRetrievedCapability != null)
                    ((DirAccess) parentRetrievedCapability.fileAccess).removeChild(this, parentRetrievedCapability.capability, signer, network);
                result.complete(true);
            });
            return result;
        }
        return ((DirAccess) fileAccess).getChildren(network, this.capability.baseKey).thenCompose(files -> {
            for (RetrievedCapability file : files)
                file.remove(network, null, signer);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            network.tree.remove(this.capability.location.owner, signer, this.capability.location.getMapKey(), fileAccess.committedHash()).thenAccept(removed -> {
                // remove from parent
                if (parentRetrievedCapability != null)
                    ((DirAccess) parentRetrievedCapability.fileAccess).removeChild(this, parentRetrievedCapability.capability, signer, network);
                result.complete(removed);
            });
            return result;
        });
    }

    public RetrievedCapability withWriter(Optional<SecretSigningKey> writer) {
        return new RetrievedCapability(new Capability(this.capability.location.owner, this.capability.location.writer,
                this.capability.location.getMapKey(), this.capability.baseKey), this.fileAccess);
    }
}
