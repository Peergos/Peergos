package peergos.shared.user.fs;

import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public interface FileRetriever {

    Location getNext();

    byte[] getNonce();

    CompletableFuture<AsyncReader> getFile(UserContext context, SymmetricKey dataKey, long fileSize,
                                           Location ourLocation, Consumer<Long> monitor);

    CompletableFuture<Optional<LocatedEncryptedChunk>> getEncryptedChunk(long bytesRemainingUntilStart,
                                                                         long bytesRemainingUntilEnd, byte[] nonce,
                                                                         SymmetricKey dataKey, Location ourLocation,
                                                                         UserContext context, Consumer<Long> monitor);

    CompletableFuture<Optional<Location>> getLocationAt(Location startLocation, long offset, UserContext context);

    CompletableFuture<Optional<LocatedChunk>> getChunkInputStream(UserContext context, SymmetricKey dataKey, long startIndex,
                                               long truncateTo, Location ourLocation, Consumer<Long> monitor);

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
