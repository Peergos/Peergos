package peergos.user.fs;

import peergos.crypto.*;
import peergos.user.*;

import java.io.*;
import java.util.*;

public class LazyInputStreamCombiner extends InputStream
{
    private final UserContext context;
    private final SymmetricKey dataKey;
    private InputStream current;
    private Optional<Location> next;

    public LazyInputStreamCombiner(EncryptedChunkRetriever stream, UserContext context, SymmetricKey dataKey) throws IOException {
        current = stream.getChunkInputStream(context, dataKey);
        next = stream.getNext();
        this.context = context;
        this.dataKey = dataKey;
    }

    private InputStream getNextStream() throws IOException {
        if (next.isPresent()) {
            EncryptedChunkRetriever nextRet = (EncryptedChunkRetriever) context.getMetadata(next.get()).getRetriever();
            next = nextRet.getNext();
            return nextRet.getChunkInputStream(context, dataKey);
        }
        throw new EOFException();
    }

    @Override
    public int read() throws IOException {
        int r = current.read();
        if (r >= 0)
            return r;
        current = getNextStream();
        return current.read();
    }
}
