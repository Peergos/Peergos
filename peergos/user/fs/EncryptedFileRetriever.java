package peergos.user.fs;

import peergos.crypto.*;
import peergos.user.*;

import java.io.*;
import java.util.*;

public class EncryptedFileRetriever implements FileRetriever
{
    private final List<EncryptedChunkRetriever> chunks;

    public EncryptedFileRetriever(List<EncryptedChunkRetriever> chunks) {
        this.chunks = chunks;
    }

    @Override
    public InputStream getFile(UserContext context, SymmetricKey dataKey) throws IOException {
        return new LazyInputStreamCombiner(chunks, context, dataKey);
    }

    public static EncryptedFileRetriever deserialize(DataInput din) throws IOException {
        int n = din.readInt();
        List<EncryptedChunkRetriever> chunks = new ArrayList<>();
        for (int i=0; i < n; i++) {
            din.readByte();// read off EncryptedChunkRetriever type
            chunks.add(EncryptedChunkRetriever.deserialize(din));
        }
        return new EncryptedFileRetriever(chunks);
    }

    @Override
    public void serialize(DataOutput dout) throws IOException {
        dout.write(Type.EncryptedFile.ordinal());
        dout.writeInt(chunks.size());
        for (EncryptedChunkRetriever chunker: chunks)
            chunker.serialize(dout);
    }
}
