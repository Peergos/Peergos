package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.cryptree.*;

import java.util.*;
import java.util.concurrent.*;

public class RetrievedFilePointer {
    public final FilePointer filePointer;
    public final CryptreeNode fileAccess;

    public RetrievedFilePointer(FilePointer filePointer, CryptreeNode fileAccess) {
        if (fileAccess == null)
            throw new IllegalStateException("Null FileAccess!");
        this.filePointer = filePointer;
        this.fileAccess = fileAccess;
    }

    public boolean equals(Object that) {
        if (that == null)
            return false;
        if (!(that instanceof RetrievedFilePointer))
            return false;
        return filePointer.equals(((RetrievedFilePointer)that).filePointer);
    }

    public CompletableFuture<Boolean> remove(NetworkAccess network, RetrievedFilePointer parentRetrievedFilePointer, SigningPrivateKeyAndPublicHash signer) {
        if (!this.filePointer.isWritable())
            return CompletableFuture.completedFuture(false);
        if (!this.fileAccess.isDirectory()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            network.btree.remove(signer, this.filePointer.location.getMapKey()).thenAccept(removed -> {
                // remove from parent
                if (parentRetrievedFilePointer != null)
                    ((DirAccess) parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, signer, network);
                result.complete(true);
            });
            return result;
        }
        return ((DirAccess)fileAccess).getChildren(network, this.filePointer.baseKey).thenCompose(files -> {
            for (RetrievedFilePointer file : files)
                file.remove(network, null, signer);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            network.btree.remove(signer, this.filePointer.location.getMapKey()).thenAccept(removed -> {
                // remove from parent
                if (parentRetrievedFilePointer != null)
                    ((DirAccess) parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, signer, network);
                result.complete(removed);
            });
            return result;
        });
    }

    public RetrievedFilePointer withWriter(Optional<SecretSigningKey> writer) {
        return new RetrievedFilePointer(new FilePointer(this.filePointer.location.owner, this.filePointer.location.writer,
                this.filePointer.location.getMapKey(), this.filePointer.baseKey), this.fileAccess);
    }
}
