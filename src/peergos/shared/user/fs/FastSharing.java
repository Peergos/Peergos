package peergos.shared.user.fs;

import peergos.shared.NetworkAccess;
import peergos.shared.crypto.random.SafeRandom;
import peergos.shared.user.EntryPoint;
import peergos.shared.util.Futures;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.*;

public class FastSharing {
    public static final int FILE_POINTER_SIZE = 159; // fp.toCbor().toByteArray() DOESN'T INCLUDE .secret
    public static final int SHARING_FILE_MAX_SIZE = FILE_POINTER_SIZE * 10000; // record size * x
    public static final String SHARING_FILE_PREFIX = "sharing.";
    public static final String SEPARATOR = "#";
    public static final String RETRIEVED_CAPABILITY_CACHE = SEPARATOR + ".cache";


    public static CompletableFuture<List<RetrievedCapability>> loadSharingLinks(FileTreeNode ourRoot,
                                                                                FileTreeNode friendSharedDir,
                                                                                String friendName,
                                                                                NetworkAccess network,
                                                                                SafeRandom random,
                                                                                Fragmenter fragmenter,
                                                                                boolean saveCache) {
        return friendSharedDir.getChildren(network)
            .thenCompose(files -> {
                List<FileTreeNode> sharingFiles = files.stream()
                        .sorted(Comparator.comparing(f -> f.getFileProperties().modified))
                        .collect(Collectors.toList());
                return ourRoot.getChild(friendName + RETRIEVED_CAPABILITY_CACHE, network).thenCompose(optCachedFile -> {
                    long totalRecords = sharingFiles.stream().mapToLong(f -> f.getFileProperties().size).sum() / FILE_POINTER_SIZE;
                    if(! optCachedFile.isPresent() ) {
                        return Futures.reduceAll(sharingFiles,
                                Collections.<RetrievedCapability>emptyList(),
                                (res, sharingFile) -> readSharingFile(friendSharedDir.getName(), sharingFile, network, random)
                                        .thenApply(retrievedCaps -> Stream.concat(res.stream(), retrievedCaps.stream()).collect(Collectors.toList())),
                                (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList())
                        ).thenCompose(res -> {
                            if(saveCache && res.size() > 0) {
                                return saveRetrievedCapabilityCache(totalRecords, ourRoot, friendName,
                                        network, random, fragmenter, res);
                            } else {
                                return CompletableFuture.completedFuture(res);
                            }
                        });
                    } else {
                        FileTreeNode cachedFile = optCachedFile.get();
                        // todo decide if we need to read files based on lastread index
                        List<FileTreeNode> unseenSharingFiles = sharingFiles.stream()
                                .filter(f -> ! cachedFile.getFileProperties().modified.isBefore(f.getFileProperties().modified))
                                .collect(Collectors.toList());
                        if (unseenSharingFiles.isEmpty()) {
                            return readRetrievedCapabilityCache(cachedFile, network, random)
                                    .thenApply(cache -> cache.getRetrievedCapabilities());
                        } else {
                            return readRetrievedCapabilityCache(cachedFile, network, random).thenCompose(cache -> {
                                int shareFileIndex = (int)(cache.getRecordsRead() * FILE_POINTER_SIZE) / SHARING_FILE_MAX_SIZE;
                                int recordIndex = (int) sharingFiles.get(sharingFiles.size() -1).getFileProperties().size / FILE_POINTER_SIZE;
                                List<FileTreeNode> sharingFilesToRead = sharingFiles.subList(shareFileIndex, sharingFiles.size());
                                return Futures.reduceAll(sharingFilesToRead.subList(0, sharingFilesToRead.size() -1),
                                        Collections.emptyList(),
                                        (res, sharingFile) -> readSharingFile(friendSharedDir.getName(), sharingFile, network, random)
                                                .thenApply(retrievedCaps -> Stream.concat(res.stream(), retrievedCaps.stream()).collect(Collectors.toList())),
                                        (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList()))
                                        .thenCompose(res -> readSharingFile(recordIndex, friendSharedDir.getName(),
                                                    sharingFilesToRead.get(sharingFilesToRead.size() -1), network, random))
                                        .thenCompose(res -> {
                                            if (saveCache) {
                                                return saveRetrievedCapabilityCache(totalRecords, ourRoot, friendName,
                                                        network, random, fragmenter, res);
                                            } else {
                                                return CompletableFuture.completedFuture(res);
                                            }
                                        });
                            });
                        }
                    }
                });
            });
    }

    public static CompletableFuture<List<RetrievedCapability>> readSharingFile(String ownerName,
                                                                               FileTreeNode file,
                                                                               NetworkAccess network,
                                                                               SafeRandom random) {
        return readSharingFile(0, ownerName, file, network, random);
    }
    public static CompletableFuture<List<RetrievedCapability>> readSharingFile(int offsetIndex,
                                                             String ownerName,
                                                             FileTreeNode file,
                                                             NetworkAccess network,
                                                             SafeRandom random) {
        return file.getInputStream(network, random, x -> {}).thenCompose(reader -> {
            int currentFileSize = (int) file.getSize();
            List<CompletableFuture<RetrievedCapability>> capabilities = IntStream.range(offsetIndex, currentFileSize / FILE_POINTER_SIZE)
                    .mapToObj(e -> e * FILE_POINTER_SIZE)
                    .map(offset -> readSharingRecord(ownerName, reader, offset, network))
                    .collect(Collectors.toList());

            return Futures.combineAllInOrder(capabilities);
        });
    }

    private static CompletableFuture<RetrievedCapability> readSharingRecord(String ownerName,
                                                                AsyncReader reader,
                                                                int offset,
                                                                NetworkAccess network) {
        byte[] serialisedFilePointer = new byte[FILE_POINTER_SIZE];
        return reader.seek( 0, offset).thenCompose( currentPos ->
                currentPos.readIntoArray(serialisedFilePointer, 0, FILE_POINTER_SIZE)
                        .thenCompose(bytesRead -> {
                            FilePointer pointer = FilePointer.fromByteArray(serialisedFilePointer);
                            EntryPoint entry = new EntryPoint(pointer, ownerName, Collections.emptySet(), Collections.emptySet());
                            return network.retrieveEntryPoint(entry).thenCompose( optFTN -> {
                                if(optFTN.isPresent()) {
                                    FileTreeNode ftn = optFTN.get();
                                    try {
                                        return ftn.getPath(network)
                                                .thenCompose(path -> CompletableFuture.completedFuture(new RetrievedCapability(path, pointer)));
                                    } catch (NoSuchElementException nsee) {
                                        return Futures.errored(nsee); //file no longer exists
                                    }
                                } else {
                                    return Futures.errored(new IllegalStateException("Unable to retrieve capability!"));
                                }
                            });
                        })
        );
    }

    public static CompletableFuture<List<RetrievedCapability>> saveRetrievedCapabilityCache(long recordsRead, FileTreeNode home, String friend,
                                                                  NetworkAccess network, SafeRandom random, Fragmenter fragmenter,
                                                                  List<RetrievedCapability> retrievedCapabilities) {
        RetrievedCapabilityCache retrievedCapabilityCache = new RetrievedCapabilityCache(recordsRead, retrievedCapabilities);
        byte[] data = retrievedCapabilityCache.serialize();
        AsyncReader.ArrayBacked dataReader = new AsyncReader.ArrayBacked(data);
        return home.uploadFile(friend + RETRIEVED_CAPABILITY_CACHE, dataReader, true, (long) data.length,
                true, network, random, x-> {}, fragmenter).thenApply(x -> retrievedCapabilities);
    }

    private static CompletableFuture<RetrievedCapabilityCache> readRetrievedCapabilityCache(FileTreeNode cacheFile, NetworkAccess network, SafeRandom random) {
        return cacheFile.getInputStream(network, random, x -> { })
                .thenCompose(reader -> {
                    byte[] storeData = new byte[(int) cacheFile.getSize()];
                    return reader.readIntoArray(storeData, 0, storeData.length)
                            .thenApply(x -> RetrievedCapabilityCache.deserialize(storeData));
                });
    }
}
