package peergos.user.fs;

import peergos.crypto.SymmetricLink;

public class FileAccess {
    public static class EncryptedFileProperties{
        //todo : wrap byte array
    }
    public static class  FileRetriever{
        //todo
    }
    public static class SymmetricLocationLink{}


    private final SymmetricLink parentToMeta;
    private final EncryptedFileProperties fileProperties;
    private final FileRetriever retriever;
    private final SymmetricLocationLink parentLink;

}
