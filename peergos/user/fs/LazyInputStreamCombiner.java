package peergos.user.fs;

import peergos.crypto.*;
import peergos.user.*;

import java.io.*;
import java.util.*;

public class LazyInputStreamCombiner extends InputStream
{
    private final List<EncryptedChunkRetriever> streams;
    private final UserContext context;
    private final SymmetricKey dataKey;
    int index = 0;
    private InputStream current;

    public LazyInputStreamCombiner(List<EncryptedChunkRetriever> streams, UserContext context, SymmetricKey dataKey) {
        this.streams = streams;
        this.context = context;
        this.dataKey = dataKey;
    }

    private InputStream getNextStream() throws IOException {
        return streams.get(index++).getFile(context, dataKey);
    }

    @Override
    public int read() throws IOException {
        while (index < streams.size()) {
            try {
                return current.read();
            } catch (IOException e) {
                if (index < streams.size())
                    current = getNextStream();
                else break;
            } catch (NullPointerException e) {
                current = getNextStream();
            }
        }
        return -1;
    }
}
