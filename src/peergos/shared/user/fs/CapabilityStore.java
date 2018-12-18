package peergos.shared.user.fs;

import peergos.shared.NetworkAccess;
import peergos.shared.cbor.*;
import peergos.shared.crypto.random.SafeRandom;
import peergos.shared.user.EntryPoint;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import java.util.stream.*;

/** This class implements the mechanism by which users share Capabilities with each other
 *
 * Each unidirectional sharing relationship has a sharing folder /source_user/sharing/recipient_user/
 * In this sharing directory is an append only list of capabilities which the source user has granted to the recipient
 * user. This is implemented as a series of numbered files in the directory with a maximum number of capabilities per
 * file. Knowing the index of the capability in the overall list you can calcualte the file name, and the offset in the
 * file at which the capability is stored. Write and read capabilities form logically separate append only lists.
 *
 * To avoid reparsing the entire capability list at every login, the capabilities and their retrieved paths are stored
 * in a cache for each source user located at /recipient_user/.capabilitycache/source_user
 * Each of these cache files is just a serialized CapabilitiesFromUser
 */
public class CapabilityStore {
    public static final int CAPABILITY_SIZE = 159; // fp.toCbor().toByteArray() DOESN'T INCLUDE .secret
    public static final int CAPS_PER_FILE = 10000;
    public static final int SHARING_FILE_MAX_SIZE = CAPABILITY_SIZE * CAPS_PER_FILE;
    public static final String CAPABILITY_CACHE_DIR = ".capabilitycache";
    public static final String READ_ONLY_SHARING_FILE_PREFIX = "sharing.r.";
    public static final String WRITE_SHARING_FILE_PREFIX = "sharing.w.";

    private static final Comparator<FileTreeNode> indexOrder =
            Comparator.comparingInt(f -> filenameToIndex(f.getName()));

    private static int filenameToIndex(String filename) {
        if (! filename.startsWith(READ_ONLY_SHARING_FILE_PREFIX))
            return -1;
        return Integer.parseInt(filename.substring(READ_ONLY_SHARING_FILE_PREFIX.length()));
    }

    private static boolean isReadCapFile(String filename) {
        return filename.startsWith(READ_ONLY_SHARING_FILE_PREFIX);
    }

    /**
     *
     * @param homeDirSupplier
     * @param friendSharedDir
     * @param friendName
     * @param network
     * @param random
     * @param fragmenter
     * @param saveCache
     * @return a pair of the current capability index, and the valid capabilities
     */
    public static CompletableFuture<CapabilitiesFromUser> loadSharingLinks(Supplier<CompletableFuture<FileTreeNode>> homeDirSupplier,
                                                                           FileTreeNode friendSharedDir,
                                                                           String friendName,
                                                                           NetworkAccess network,
                                                                           SafeRandom random,
                                                                           Fragmenter fragmenter,
                                                                           boolean saveCache) {
        return friendSharedDir.getChildren(network)
            .thenCompose(files -> {
                List<FileTreeNode> sharingFiles = files.stream()
                        .filter(f -> isReadCapFile(f.getName()))
                        .sorted(indexOrder)
                        .collect(Collectors.toList());
                return getCacheFile(friendName, homeDirSupplier, network, random).thenCompose(optCachedFile -> {
                    long totalRecords = sharingFiles.stream().mapToLong(f -> f.getFileProperties().size).sum() / CAPABILITY_SIZE;
                    if(! optCachedFile.isPresent() ) {
                        CompletableFuture<List<CapabilityWithPath>> allFiles = Futures.reduceAll(sharingFiles,
                                Collections.emptyList(),
                                (res, sharingFile) -> readSharingFile(friendSharedDir.getName(), sharingFile, network, random)
                                        .thenApply(retrievedCaps -> Stream.concat(res.stream(), retrievedCaps.stream()).collect(Collectors.toList())),
                                (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList())
                        );
                        return allFiles.thenCompose(res -> {
                            if(saveCache && res.size() > 0) {
                                return saveRetrievedCapabilityCache(totalRecords, homeDirSupplier, friendName,
                                        network, random, fragmenter, res);
                            } else {
                                return CompletableFuture.completedFuture(new CapabilitiesFromUser(totalRecords, res));
                            }
                        });
                    } else {
                        FileTreeNode cachedFile = optCachedFile.get();
                        return readRetrievedCapabilityCache(cachedFile, network, random).thenCompose(cache -> {
                            if (totalRecords == cache.getRecordsRead())
                                return CompletableFuture.completedFuture(cache);
                            int shareFileIndex = (int)(cache.getRecordsRead() * CAPABILITY_SIZE) / SHARING_FILE_MAX_SIZE;
                            int recordIndex = (int) ((cache.getRecordsRead() * CAPABILITY_SIZE) % SHARING_FILE_MAX_SIZE) / CAPABILITY_SIZE;
                            List<FileTreeNode> sharingFilesToRead = sharingFiles.subList(shareFileIndex, sharingFiles.size());
                            CompletableFuture<List<CapabilityWithPath>> allFiles = Futures.reduceAll(sharingFilesToRead.subList(0, sharingFilesToRead.size() - 1),
                                    Collections.emptyList(),
                                    (res, sharingFile) -> readSharingFile(friendSharedDir.getName(), sharingFile, network, random)
                                            .thenApply(retrievedCaps -> Stream.concat(res.stream(), retrievedCaps.stream()).collect(Collectors.toList())),
                                    (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList()));
                            return allFiles
                                    .thenCompose(res -> readSharingFile(recordIndex, friendSharedDir.getName(),
                                            sharingFilesToRead.get(sharingFilesToRead.size() -1), network, random))
                                    .thenCompose(res -> {
                                        if (saveCache) {
                                            return saveRetrievedCapabilityCache(totalRecords, homeDirSupplier, friendName,
                                                    network, random, fragmenter, res);
                                        } else {
                                            return CompletableFuture.completedFuture(new CapabilitiesFromUser(totalRecords, res));
                                        }
                                    });
                            });
                    }
                });
            });
    }

    public static CompletableFuture<CapabilitiesFromUser> loadSharingLinksFromIndex(Supplier<CompletableFuture<FileTreeNode>> homeDirSupplier,
                                                                                    FileTreeNode friendSharedDir,
                                                                                    String friendName,
                                                                                    NetworkAccess network,
                                                                                    SafeRandom random,
                                                                                    Fragmenter fragmenter,
                                                                                    long capIndex,
                                                                                    boolean saveCache) {
        return friendSharedDir.getChildren(network)
                .thenCompose(files -> {
                    List<FileTreeNode> sharingFiles = files.stream()
                            .sorted(indexOrder)
                            .collect(Collectors.toList());
                    long totalRecords = sharingFiles.stream().mapToLong(f -> f.getFileProperties().size).sum() / CAPABILITY_SIZE;
                    int shareFileIndex = (int) (capIndex * CAPABILITY_SIZE) / SHARING_FILE_MAX_SIZE;
                    int recordIndex = (int) (capIndex % CAPS_PER_FILE);
                    List<FileTreeNode> sharingFilesToRead = sharingFiles.subList(shareFileIndex, sharingFiles.size());

                    CompletableFuture<List<CapabilityWithPath>> allFiles = Futures.reduceAll(sharingFilesToRead.subList(0, sharingFilesToRead.size() - 1),
                            Collections.emptyList(),
                            (res, sharingFile) -> readSharingFile(friendSharedDir.getName(), sharingFile, network, random)
                                    .thenApply(retrievedCaps -> Stream.concat(res.stream(), retrievedCaps.stream()).collect(Collectors.toList())),
                            (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList()));
                    CompletableFuture<CapabilitiesFromUser> result = allFiles
                            .thenCompose(res -> readSharingFile(recordIndex, friendSharedDir.getName(),
                                    sharingFilesToRead.get(sharingFilesToRead.size() - 1), network, random))
                            .thenCompose(res -> {
                                if (saveCache) {
                                    return saveRetrievedCapabilityCache(totalRecords - capIndex, homeDirSupplier, friendName,
                                            network, random, fragmenter, res);
                                } else {
                                    return CompletableFuture.completedFuture(new CapabilitiesFromUser(totalRecords - capIndex, res));
                                }
                            });
                    return result;
                });
    }

    public static CompletableFuture<Long> getCapabilityCount(FileTreeNode friendSharedDir,
                                                             NetworkAccess network) {
        return friendSharedDir.getChildren(network)
                .thenApply(capFiles -> capFiles.stream().mapToLong(f -> f.getFileProperties().size).sum() / CAPABILITY_SIZE);
    }

    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(String ownerName,
                                                                              FileTreeNode file,
                                                                              NetworkAccess network,
                                                                              SafeRandom random) {
        return readSharingFile(0, ownerName, file, network, random);
    }
    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(int offsetIndex,
                                                                              String ownerName,
                                                                              FileTreeNode file,
                                                                              NetworkAccess network,
                                                                              SafeRandom random) {
        return file.getInputStream(network, random, x -> {}).thenCompose(reader -> {
            int currentFileSize = (int) file.getSize();
            List<CompletableFuture<Optional<CapabilityWithPath>>> capabilities = IntStream.range(offsetIndex, currentFileSize / CAPABILITY_SIZE)
                    .mapToObj(e -> e * CAPABILITY_SIZE)
                    .map(offset -> readSharingRecord(ownerName, reader, offset, network))
                    .collect(Collectors.toList());

            return Futures.combineAllInOrder(capabilities).thenApply(optList -> optList.stream()
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList()));
        });
    }

    private static CompletableFuture<Optional<CapabilityWithPath>> readSharingRecord(String ownerName,
                                                                                     AsyncReader reader,
                                                                                     int offset,
                                                                                     NetworkAccess network) {
        byte[] serialisedFilePointer = new byte[CAPABILITY_SIZE];
        return reader.seek( 0, offset).thenCompose( currentPos ->
                currentPos.readIntoArray(serialisedFilePointer, 0, CAPABILITY_SIZE)
                        .thenCompose(bytesRead -> {
                            Capability pointer = Capability.fromByteArray(serialisedFilePointer);
                            EntryPoint entry = new EntryPoint(pointer, ownerName, Collections.emptySet(), Collections.emptySet());
                            return network.retrieveEntryPoint(entry).thenCompose( optFTN -> {
                                if(optFTN.isPresent()) {
                                    FileTreeNode ftn = optFTN.get();
                                    try {
                                        return ftn.getPath(network)
                                                .thenCompose(path -> CompletableFuture.completedFuture(Optional.of(new CapabilityWithPath(path, pointer))));
                                    } catch (NoSuchElementException nsee) {
                                        return Futures.errored(nsee); //file no longer exists
                                    }
                                } else {
                                    return CompletableFuture.completedFuture(Optional.empty());
                                }
                            });
                        })
        );
    }

    private static CompletableFuture<Optional<FileTreeNode>> getCacheFile(String friendName,
                                                                          Supplier<CompletableFuture<FileTreeNode>> getHome,
                                                                          NetworkAccess network,
                                                                          SafeRandom random) {
        return getCapabilityCacheDir(getHome, network, random)
                .thenCompose(cacheDir -> cacheDir.getChild(friendName, network));
    }

    private static CompletableFuture<FileTreeNode> getCapabilityCacheDir(Supplier<CompletableFuture<FileTreeNode>> getHome,
                                                                         NetworkAccess network,
                                                                         SafeRandom random) {
        return getHome.get()
                .thenCompose(home -> home.getChild(CAPABILITY_CACHE_DIR, network)
                        .thenCompose(opt ->
                                opt.map(CompletableFuture::completedFuture)
                                        .orElseGet(() -> home.mkdir(CAPABILITY_CACHE_DIR, network, true, random)
                                                .thenCompose(x -> getCapabilityCacheDir(getHome, network, random)))));
    }

    public static CompletableFuture<CapabilitiesFromUser> saveRetrievedCapabilityCache(long recordsRead,
                                                                                       Supplier<CompletableFuture<FileTreeNode>> homeDirSupplier,
                                                                                       String friend,
                                                                                       NetworkAccess network,
                                                                                       SafeRandom random,
                                                                                       Fragmenter fragmenter,
                                                                                       List<CapabilityWithPath> retrievedCapabilities) {
        CapabilitiesFromUser capabilitiesFromUser = new CapabilitiesFromUser(recordsRead, retrievedCapabilities);
        byte[] data = capabilitiesFromUser.serialize();
        AsyncReader.ArrayBacked dataReader = new AsyncReader.ArrayBacked(data);
        return getCapabilityCacheDir(homeDirSupplier, network, random)
                .thenCompose(cacheDir -> cacheDir.uploadFile(friend, dataReader, true, (long) data.length,
                true, network, random, x-> {}, fragmenter).thenApply(x -> capabilitiesFromUser));
    }

    private static CompletableFuture<CapabilitiesFromUser> readRetrievedCapabilityCache(FileTreeNode cacheFile, NetworkAccess network, SafeRandom random) {
        return cacheFile.getInputStream(network, random, x -> { })
                .thenCompose(reader -> {
                    byte[] storeData = new byte[(int) cacheFile.getSize()];
                    return reader.readIntoArray(storeData, 0, storeData.length)
                            .thenApply(x -> CapabilitiesFromUser.fromCbor(CborObject.fromByteArray(storeData)));
                });
    }
}
