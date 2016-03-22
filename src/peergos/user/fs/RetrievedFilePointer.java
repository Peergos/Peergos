package peergos.user.fs;

import peergos.crypto.*;
import peergos.user.*;

import java.io.*;
import java.util.*;

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

    public boolean remove(UserContext context, RetrievedFilePointer parentRetrievedFilePointer) throws IOException {
        if (!this.filePointer.isWritable())
            return false;
        if (!this.fileAccess.isDirectory()) {
            return this.fileAccess.removeFragments(context);
            byte[] treeRootHashCAS = context.btree.remove(this.filePointer.writer.getPublicKeys(), this.filePointer.mapKey);
            byte[] signed = ((User)filePointer.writer).signMessage(treeRootHashCAS);
            context.corenodeClient.setMetadataBlob(this.filePointer.owner.getPublicKeys(), this.filePointer.writer.getPublicKeys(), signed);
            // remove from parent
            if (parentRetrievedFilePointer != null)
                ((DirAccess)parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, context);
        }
        Set<RetrievedFilePointer> files = ((DirAccess)fileAccess).getChildren(context, this.filePointer.baseKey);
        for (RetrievedFilePointer file: files)
            file.remove(context, null);
        byte[] treeRootHashCAS = context.btree.remove(this.filePointer.writer.getPublicKeys(), this.filePointer.mapKey);
        byte[] signed = ((User)filePointer.writer).signMessage(treeRootHashCAS);
        return context.corenodeClient.setMetadataBlob(this.filePointer.owner.getPublicKeys(), this.filePointer.writer.getPublicKeys(), signed);
        // remove from parent
        if (parentRetrievedFilePointer != null)
            ((DirAccess)parentRetrievedFilePointer.fileAccess).removeChild(this, parentRetrievedFilePointer.filePointer, context);
    }

    public RetrievedFilePointer withWriter(UserPublicKey writer) {
        return new RetrievedFilePointer(new ReadableFilePointer(this.filePointer.owner, writer, this.filePointer.mapKey, this.filePointer.baseKey), this.fileAccess);
    }
}
