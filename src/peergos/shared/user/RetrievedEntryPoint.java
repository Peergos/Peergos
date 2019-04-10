package peergos.shared.user;

import peergos.shared.user.fs.*;

public class RetrievedEntryPoint {

    public final EntryPoint entry;
    public final String path;
    public final FileWrapper file;

    public RetrievedEntryPoint(EntryPoint entry, String path, FileWrapper file) {
        this.entry = entry;
        this.path = path;
        this.file = file;
    }

    public String getPath() {
        return path;
    }
}
