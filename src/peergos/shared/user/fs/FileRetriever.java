package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface FileRetriever {

    CompletableFuture<AsyncReader> getFile(WriterData version,
                                           NetworkAccess network,
                                           Crypto crypto,
                                           AbsoluteCapability ourCap,
                                           Optional<byte[]> streamSecret,
                                           long fileSize,
                                           MaybeMultihash ourExistingHash,
                                           int nBufferedChunks,
                                           ProgressConsumer<Long> monitor);

    CompletableFuture<Optional<Pair<byte[], Optional<Bat>>>> getMapLabelAt(WriterData version,
                                                                           AbsoluteCapability startCap,
                                                                           Optional<byte[]> streamSecret,
                                                                           long offset,
                                                                           Hasher hasher,
                                                                           NetworkAccess network);

    CompletableFuture<Optional<LocatedChunk>> getChunk(WriterData version,
                                                       NetworkAccess network,
                                                       Crypto crypto,
                                                       long startIndex,
                                                       long truncateTo,
                                                       AbsoluteCapability ourCap,
                                                       Optional<byte[]> streamSecret,
                                                       MaybeMultihash ourExistingHash,
                                                       ProgressConsumer<Long> monitor);
}
