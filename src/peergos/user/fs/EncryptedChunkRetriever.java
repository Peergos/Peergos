package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.user.*;
import peergos.user.fs.erasure.*;
import peergos.util.*;

import java.io.*;
import java.util.*;

public class EncryptedChunkRetriever implements FileRetriever {
    private final byte[] chunkNonce;
    private final byte[] chunkAuth;
    private final List<ByteArrayWrapper> fragmentHashes;
    private final Optional<Location> nextChunk;

    public EncryptedChunkRetriever(byte[] chunkNonce, byte[] chunkAuth, List<ByteArrayWrapper> fragmentHashes, Optional<Location> nextChunk) {
        this.chunkNonce = chunkNonce;
        this.chunkAuth = chunkAuth;
        this.fragmentHashes = fragmentHashes;
        this.nextChunk = nextChunk;
    }

    @Override
    public InputStream getFile(UserContext context, SymmetricKey dataKey) throws IOException {
        return new LazyInputStreamCombiner(this, context, dataKey);
    }

    public InputStream getChunkInputStream(UserContext context, SymmetricKey dataKey) throws IOException {
        Fragment[] retrievedfragments1 = context.downloadFragments(fragmentHashes);
        byte[] enc1 = Erasure.recombine(reorder(fragmentHashes, retrievedfragments1), Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
        EncryptedChunk encrypted1 = new EncryptedChunk(ArrayOps.concat(chunkAuth, enc1));
        byte[] original = encrypted1.decrypt(dataKey, chunkNonce);
        return new ByteArrayInputStream(original);
    }

    public Optional<Location> getNext() {
        return nextChunk;
    }

    public static byte[][] reorder(List<ByteArrayWrapper> hashes, Fragment[] received)
    {
        byte[][] originalHashes = new byte[hashes.size()][];
        for (int i=0; i < originalHashes.length; i++)
            originalHashes[i] = hashes.get(i).data;
        byte[][] res = new byte[originalHashes.length][];
        for (int i=0; i < res.length; i++)
        {
            for (int j=0; j < received.length; j++)
                if (Arrays.equals(originalHashes[i], received[j].getHash()))
                {
                    res[i] = received[j].getData();
                    break;
                }
            if (res[i] == null)
                res[i] = new byte[received[0].getData().length];
        }
        return res;
    }

    public void serialize(DataOutput dout) throws IOException {
        dout.write(Type.EncryptedChunk.ordinal());
        Serialize.serialize(chunkNonce, dout);
        Serialize.serialize(chunkAuth, dout);
        Serialize.serialize(ArrayOps.concat(fragmentHashes), dout);
        dout.writeBoolean(nextChunk.isPresent());
        if (nextChunk.isPresent())
            nextChunk.get().serialise(dout);
    }

    public static EncryptedChunkRetriever deserialize(DataInputStream din) throws IOException {
        byte[] chunkNonce = Serialize.deserializeByteArray(din, SymmetricKey.NONCE_BYTES);
        byte[] chunkAuth = Serialize.deserializeByteArray(din, TweetNaCl.SECRETBOX_OVERHEAD_BYTES);
        // TODO change to safer serialization (don't assume hash size)
        List<ByteArrayWrapper> fragmentHashes =
                ArrayOps.split(Serialize.deserializeByteArray(din, EncryptedChunk.ERASURE_ORIGINAL * Fragment.SIZE), Hash.HASH_BYTES);
        boolean hasNext = din.readBoolean();
        Optional<Location> next = hasNext ? Optional.of(Location.deserialise(din)) : Optional.empty();
        return new EncryptedChunkRetriever(chunkNonce, chunkAuth, fragmentHashes, next);
    }
}
