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
                                             PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             SigningPrivateKeyAndPublicHash signer) {
        if (! capability.isWritable())
            return CompletableFuture.completedFuture(false);
        Location loc = this.capability.getLocation(owner, writer);
        if (! fileAccess.isDirectory()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Transaction.run(loc.owner,
                    tid -> network.tree.remove(loc.owner, signer, loc.getMapKey(), fileAccess.committedHash(), tid).thenAccept(removed -> {
                        // remove from parent
                        if (parentRetrievedCapability != null)
                            ((DirAccess) parentRetrievedCapability.fileAccess)
                                    .removeChild(this, owner, parentRetrievedCapability.capability, signer, network);
                        result.complete(true);
                    }), network.dhtClient);
            return result;
        }
        return ((DirAccess) fileAccess).getChildren(network, owner, this.capability.writer.orElse(writer), this.capability.baseKey).thenCompose(files -> {
            for (RetrievedCapability file : files)
                file.remove(network, null, owner, writer, signer);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Transaction.run(loc.owner,
                    tid -> network.tree.remove(loc.owner, signer, loc.getMapKey(), fileAccess.committedHash(), tid).thenAccept(removed -> {
                        // remove from parent
                        if (parentRetrievedCapability != null)
                            ((DirAccess) parentRetrievedCapability.fileAccess).removeChild(this, owner, parentRetrievedCapability.capability, signer, network);
                        result.complete(removed);
                    }), network.dhtClient);
            return result;
        });
    }

    public RetrievedCapability withCryptree(CryptreeNode fileAccess) {
        return new RetrievedCapability(capability, fileAccess);
    }

    public RetrievedCapability withWriter(Optional<SecretSigningKey> writer) {
        Capability cap = new Capability(capability.writer, capability.getMapKey(), capability.baseKey, writer);
        return new RetrievedCapability(cap, this.fileAccess);
    }
}
