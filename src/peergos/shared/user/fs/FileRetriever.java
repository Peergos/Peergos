package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface FileRetriever extends Cborable {

    Optional<Location> getNext(SymmetricKey dataKey);

    byte[] getNonce();

    CompletableFuture<AsyncReader> getFile(UserContext context, SymmetricKey dataKey, long fileSize,
                                           Location ourLocation, ProgressConsumer<Long> monitor);

    CompletableFuture<Optional<LocatedEncryptedChunk>> getEncryptedChunk(long bytesRemainingUntilStart,
                                                                         long bytesRemainingUntilEnd, byte[] nonce,
                                                                         SymmetricKey dataKey, Location ourLocation,
                                                                         UserContext context, ProgressConsumer<Long> monitor);

    CompletableFuture<Optional<Location>> getLocationAt(Location startLocation, long offset, SymmetricKey dataKey, UserContext context);

    CompletableFuture<Optional<LocatedChunk>> getChunkInputStream(UserContext context, SymmetricKey dataKey, long startIndex,
                                               long truncateTo, Location ourLocation, ProgressConsumer<Long> monitor);

    static FileRetriever fromCbor(CborObject cbor) {
        return EncryptedChunkRetriever.fromCbor(cbor);
    }
}
