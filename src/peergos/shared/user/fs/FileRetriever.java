package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface FileRetriever {

    CompletableFuture<AsyncReader> getFile(NetworkAccess network,
                                           SafeRandom random,
                                           SymmetricKey baseKey,
                                           long fileSize,
                                           Location ourLocation,
                                           MaybeMultihash ourExistingHash,
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
}
