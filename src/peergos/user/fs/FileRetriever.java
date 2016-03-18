package peergos.user.fs;

import java.io.*;

public interface FileRetriever {

    Location getNext();

    static FileRetriever deserialize(DataInputStream bin) throws IOException {
        byte type = bin.readByte();
        switch (type) {
            case 0:
                throw new Exception("Simple FileRetriever not implemented!");
            case 1:
                return EncryptedChunkRetriever.deserialize(bin);
            default:
                throw new Exception("Unknown FileRetriever type: "+type);
        }
    }
}
