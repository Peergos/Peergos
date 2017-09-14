package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.merklebtree.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface FileRetriever extends Cborable {

    Optional<Location> getNext(SymmetricKey dataKey);

    byte[] getNonce();

    CompletableFuture<AsyncReader> getFile(NetworkAccess network,
                                           SafeRandom random,
                                           SymmetricKey dataKey,
                                           long fileSize,
                                           Location ourLocation,
                                           MaybeMultihash ourExistingHash,
                                           ProgressConsumer<Long> monitor);

    CompletableFuture<Optional<LocatedEncryptedChunk>> getEncryptedChunk(long bytesRemainingUntilStart,
                                                                         long bytesRemainingUntilEnd,
                                                                         byte[] nonce,
                                                                         SymmetricKey dataKey,
                                                                         Location ourLocation,
                                                                         MaybeMultihash ourExistingHash,
                                                                         NetworkAccess network,
                                                                         ProgressConsumer<Long> monitor);

    CompletableFuture<Optional<Location>> getLocationAt(Location startLocation,
                                                        long offset,
                                                        SymmetricKey dataKey,
                                                        NetworkAccess network);

    CompletableFuture<Optional<LocatedChunk>> getChunkInputStream(NetworkAccess network,
                                                                  SafeRandom random,
                                                                  SymmetricKey dataKey,
                                                                  long startIndex,
                                                                  long truncateTo,
                                                                  Location ourLocation,
                                                                  MaybeMultihash ourExistingHash,
                                                                  ProgressConsumer<Long> monitor);

    static FileRetriever fromCbor(CborObject cbor) {
        return EncryptedChunkRetriever.fromCbor(cbor);
    }
}
