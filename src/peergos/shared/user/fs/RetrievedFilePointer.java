package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.merklebtree.*;
import peergos.shared.user.*;

import java.io.*;
import java.util.*;
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
            context.btree.remove(this.filePointer.location.writer, this.filePointer.location.getMapKey()).thenAccept(treeRootHashCAS -> {
                try {
                    byte[] signed = ((User) filePointer.location.writer).signMessage(treeRootHashCAS.toByteArray());
                    context.corenodeClient.setMetadataBlob(this.filePointer.location.owner, this.filePointer.location.writer, signed);
                    // remove from parent
                    if (parentRetrievedFilePointer != null)
                        ((DirAccess) parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, context);
                    result.complete(true);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            return result;
        }
        return ((DirAccess)fileAccess).getChildren(context, this.filePointer.baseKey).thenCompose(files -> {
            for (RetrievedFilePointer file : files)
                file.remove(context, null);
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            context.btree.remove(this.filePointer.location.writer, this.filePointer.location.getMapKey()).thenAccept(treeRootHashCAS -> {
                try {
                    byte[] signed = ((User) filePointer.location.writer).signMessage(treeRootHashCAS.toByteArray());
                    context.corenodeClient.setMetadataBlob(this.filePointer.location.owner, this.filePointer.location.writer, signed).thenAccept(res -> {
                        // remove from parent
                        if (parentRetrievedFilePointer != null)
                            ((DirAccess) parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, context);
                        result.complete(res);
                    });
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            return result;
        });
    }

    public RetrievedFilePointer withWriter(UserPublicKey writer) {
        return new RetrievedFilePointer(new ReadableFilePointer(this.filePointer.location.owner, writer,
                this.filePointer.location.getMapKey(), this.filePointer.baseKey), this.fileAccess);
    }
}
