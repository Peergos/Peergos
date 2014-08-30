package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.user.UserContext;

public class FileWrapper
{
    private UserContext context;
    private SymmetricKey baseKey;
    private Metadata first;
    private FileProperties props;

    public FileWrapper(UserContext context, Metadata meta, SymmetricKey baseKey) {
        this.context = context;
        this.first = meta;
        this.baseKey = baseKey;
        this.props = meta.getProps(baseKey);
    }

    public boolean isDir() {
        return first instanceof DirAccess;
    }

    public FileProperties props() {
        return props;
    }
}
