package peergos.user.fs;

import peergos.crypto.symmetric.*;
import peergos.user.*;
import peergos.util.*;

import java.io.*;
import java.util.function.*;

public interface FileRetriever {

    Location getNext();

    LazyInputStreamCombiner getFile(UserContext context, SymmetricKey dataKey, long len, Consumer<Long> monitor);

    byte[] getChunkInputStream(UserContext context, SymmetricKey dataKey, long len, Consumer<Long> monitor);

    void serialize(DataSink sink);

    static FileRetriever deserialize(DataSource bin) throws IOException {
        byte type = bin.readByte();
        switch (type) {
            case 0:
                throw new IllegalStateException("Simple FileRetriever not implemented!");
            case 1:
                return EncryptedChunkRetriever.deserialize(bin);
            default:
                throw new IllegalStateException("Unknown FileRetriever type: "+type);
        }
    }
}
