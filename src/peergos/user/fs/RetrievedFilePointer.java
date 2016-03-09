package peergos.user.fs;

/**
 * Created by chrirs on 09/03/16.
 */
public class RetrievedFilePointer {
    public final ReadableFilePointer readableFilePointer;
    public final FileAccess fileAccess;

    public RetrievedFilePointer(ReadableFilePointer readableFilePointer, FileAccess fileAccess) {
        this.readableFilePointer = readableFilePointer;
        this.fileAccess = fileAccess;
    }
}
