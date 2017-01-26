package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

public class RetrievedFilePointer {
    public final FilePointer filePointer;
    public final FileAccess fileAccess;

    public RetrievedFilePointer(FilePointer filePointer, FileAccess fileAccess) {
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

    public CompletableFuture<Boolean> remove(UserContext context, RetrievedFilePointer parentRetrievedFilePointer, SigningKeyPair signer) {
        if (!this.filePointer.isWritable())
            return CompletableFuture.completedFuture(false);
        if (!this.fileAccess.isDirectory()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            context.network.btree.remove(signer, this.filePointer.location.getMapKey()).thenAccept(removed -> {
                // remove from parent
                if (parentRetrievedFilePointer != null)
                    ((DirAccess) parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, signer, context);
                result.complete(true);
            });
            return result;
        }
        return ((DirAccess)fileAccess).getChildren(context, this.filePointer.baseKey).thenCompose(files -> {
            for (RetrievedFilePointer file : files)
                file.remove(context, null, signer);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            context.network.btree.remove(signer, this.filePointer.location.getMapKey()).thenAccept(removed -> {
                // remove from parent
                if (parentRetrievedFilePointer != null)
                    ((DirAccess) parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, signer, context);
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
