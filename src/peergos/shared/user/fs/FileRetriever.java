package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface FileRetriever extends Cborable {

    /**
     *
     * @param dataKey
     * @return the map key of the next chunk in this file
     */
    Optional<byte[]> getNextMapLabel(SymmetricKey dataKey);

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
                                                                         AbsoluteCapability ourCap,
                                                                         MaybeMultihash ourExistingHash,
                                                                         NetworkAccess network,
                                                                         ProgressConsumer<Long> monitor);

    CompletableFuture<Optional<byte[]>> getMapLabelAt(AbsoluteCapability startCap,
                                                      long offset,
                                                      NetworkAccess network);

    CompletableFuture<Optional<LocatedChunk>> getChunkInputStream(NetworkAccess network,
                                                                  SafeRandom random,
                                                                  long startIndex,
                                                                  long truncateTo,
                                                                  AbsoluteCapability ourLocation,
                                                                  MaybeMultihash ourExistingHash,
                                                                  ProgressConsumer<Long> monitor);

    static FileRetriever fromCbor(Cborable cbor) {
        return EncryptedChunkRetriever.fromCbor(cbor);
    }
}
