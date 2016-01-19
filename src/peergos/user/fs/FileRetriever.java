package peergos.user.fs;

import peergos.crypto.symmetric.SymmetricKey;
import peergos.user.*;

import java.io.*;

public interface FileRetriever
{
    enum Type {Simple, EncryptedChunk}

    InputStream getFile(UserContext context, SymmetricKey dataKey) throws IOException;

    void serialize(DataOutput dout) throws IOException;

    class Simple implements FileRetriever {
        File source;

        public Simple(File source) {
            this.source = source;
        }

        public InputStream getFile(UserContext context, SymmetricKey dataKey) throws IOException {
            return new FileInputStream(source);
        }

        public void serialize(DataOutput dout) throws IOException {
            dout.write(Type.Simple.ordinal());
            dout.writeUTF(source.getPath());
        }

        public static Simple deserialize(DataInput din) throws IOException {
            return new Simple(new File(din.readUTF()));
        }
    }

    static FileRetriever deserialize(DataInputStream din) throws IOException {
        int type = din.readByte() & 0xff;
        switch (Type.values()[type]) {
            case Simple:
                return Simple.deserialize(din);
            case EncryptedChunk:
                return EncryptedChunkRetriever.deserialize(din);
            default:
                throw new IllegalStateException("Unknown FileRetriever type: "+type);
        }
    }
}
