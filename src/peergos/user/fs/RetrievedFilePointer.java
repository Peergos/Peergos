package peergos.user.fs;

import peergos.user.UserContext;

public class RetrievedFilePointer {
    public final ReadableFilePointer readableFilePointer;
    public final FileAccess fileAccess;

    public RetrievedFilePointer(ReadableFilePointer readableFilePointer, FileAccess fileAccess) {
        this.readableFilePointer = readableFilePointer;
        this.fileAccess = fileAccess;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RetrievedFilePointer that = (RetrievedFilePointer) o;

        return readableFilePointer != null ? readableFilePointer.equals(that.readableFilePointer) : that.readableFilePointer == null;

    }

    @Override
    public int hashCode() {
        return readableFilePointer != null ? readableFilePointer.hashCode() : 0;
    }
    
    public boolean remove(UserContext userContext, RetrievedFilePointer parent) {
        if (! readableFilePointer.isWritable())
            return false;
        // TODO: 09/03/16
        return true;
    }
}
