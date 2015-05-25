package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.*;

import java.io.*;
import java.util.*;

public interface FileRetriever
{
    enum Type {Simple, Encrypted}

    InputStream getFile() throws IOException;

    void serialize(DataOutput dout) throws IOException;

    class Encrypted implements FileRetriever {
        private final byte[] chunkNonce;
        private final byte[] chunkAuth;
        private final List<ByteArrayWrapper> fragmentHashes;

        public Encrypted(byte[] chunkNonce, byte[] chunkAuth, List<ByteArrayWrapper> fragmentHashes) {
            this.chunkNonce = chunkNonce;
            this.chunkAuth = chunkAuth;
            this.fragmentHashes = fragmentHashes;
        }

        @Override
        public InputStream getFile() throws IOException {
            return null;
        }

        public void serialize(DataOutput dout) throws IOException {

        }

        public static Encrypted deserialize(DataInput din) throws IOException {
            byte[] chunkNonce = Serialize.deserializeByteArray(din, SymmetricKey.NONCE_BYTES);
            byte[] chunkAuth = Serialize.deserializeByteArray(din, TweetNaCl.SECRETBOX_OVERHEAD_BYTES);
            List<ByteArrayWrapper> fragmentHashes =
                    ArrayOps.split(Serialize.deserializeByteArray(din, EncryptedChunk.ERASURE_ORIGINAL*Fragment.SIZE), Fragment.SIZE);
            return new Encrypted(chunkNonce, chunkAuth, fragmentHashes);
        }
    }

    class Simple implements FileRetriever {
        File source;

        public Simple(File source) {
            this.source = source;
        }

        public InputStream getFile() throws IOException {
            return new FileInputStream(source);
        }

        public void serialize(DataOutput dout) throws IOException {
            dout.writeUTF(source.getPath());
        }

        public static Simple deserialize(DataInput din) throws IOException {
            return new Simple(new File(din.readUTF()));
        }
    }

    static FileRetriever deserialize(DataInput din) throws IOException {
        int type = din.readByte() & 0xff;
        switch (Type.values()[type]) {
            case Simple:
                return Simple.deserialize(din);
            case Encrypted:
                return Encrypted.deserialize(din);
            default:
                throw new IllegalStateException("Unknown FileRetriever type: "+type);
        }
    }
}
