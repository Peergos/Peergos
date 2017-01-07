package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.user.*;

import java.util.concurrent.*;

public class RetrievedFilePointer {
    public final ReadableFilePointer filePointer;
    public final FileAccess fileAccess;

    public RetrievedFilePointer(ReadableFilePointer filePointer, FileAccess fileAccess) {
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

    public CompletableFuture<Boolean> remove(UserContext context, RetrievedFilePointer parentRetrievedFilePointer) {
        if (!this.filePointer.isWritable())
            return CompletableFuture.completedFuture(false);
        if (!this.fileAccess.isDirectory()) {
            this.fileAccess.removeFragments(context);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            context.network.btree.remove((SigningKeyPair) this.filePointer.location.writer, this.filePointer.location.getMapKey()).thenAccept(removed -> {
                // remove from parent
                if (parentRetrievedFilePointer != null)
                    ((DirAccess) parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, context);
                result.complete(true);
            });
            return result;
        }
        return ((DirAccess)fileAccess).getChildren(context, this.filePointer.baseKey).thenCompose(files -> {
            for (RetrievedFilePointer file : files)
                file.remove(context, null);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            context.network.btree.remove((SigningKeyPair) this.filePointer.location.writer, this.filePointer.location.getMapKey()).thenAccept(removed -> {
                // remove from parent
                if (parentRetrievedFilePointer != null)
                    ((DirAccess) parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, context);
                result.complete(removed);
            });
            return result;
        });
    }

    public RetrievedFilePointer withWriter(UserPublicKey writer) {
        return new RetrievedFilePointer(new ReadableFilePointer(this.filePointer.location.owner, writer,
                this.filePointer.location.getMapKey(), this.filePointer.baseKey), this.fileAccess);
    }
}
