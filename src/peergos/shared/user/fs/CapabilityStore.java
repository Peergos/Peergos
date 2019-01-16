package peergos.shared.user.fs;

import peergos.shared.NetworkAccess;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
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
    public static final int READ_CAPABILITY_SIZE = 162; // fp.toCbor().toByteArray() DOESN'T INCLUDE .secret
    public static final int EDIT_CAPABILITY_SIZE = 162;
    private static final int CAPS_PER_FILE = 10000;
    public static final int SHARING_READ_FILE_MAX_SIZE = READ_CAPABILITY_SIZE * CAPS_PER_FILE;
    public static final int SHARING_EDIT_FILE_MAX_SIZE = EDIT_CAPABILITY_SIZE * CAPS_PER_FILE;

    private static final String CAPABILITY_CACHE_DIR = ".capabilitycache";
    private static final String CAPABILITY_READ = ".r.";
    private static final String CAPABILITY_EDIT = ".w.";
    public static final String READ_SHARING_FILE_PREFIX = "sharing" + CAPABILITY_READ;
    public static final String EDIT_SHARING_FILE_PREFIX = "sharing" + CAPABILITY_EDIT;

    private static final Comparator<FileWrapper> readOnlyCapabilitiesInIndexOrder =
            Comparator.comparingInt(f -> filenameToIndex(f.getName(), READ_SHARING_FILE_PREFIX));

    private static final Comparator<FileWrapper> editCapabilitiesInIndexOrder =
            Comparator.comparingInt(f -> filenameToIndex(f.getName(), EDIT_SHARING_FILE_PREFIX));

    private static int filenameToIndex(String filename, String capabilityType) {
        if (! filename.startsWith(capabilityType))
            return -1;
        return Integer.parseInt(filename.substring(capabilityType.length()));
    }

    private static boolean isReadCapFile(String filename) {
        return filename.startsWith(READ_SHARING_FILE_PREFIX);
    }

    private static boolean isEditCapFile(String filename) {
        return filename.startsWith(EDIT_SHARING_FILE_PREFIX);
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
    public static CompletableFuture<CapabilitiesFromUser> loadReadAccessSharingLinks(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                     FileWrapper friendSharedDir,
                                                                                     String friendName,
                                                                                     NetworkAccess network,
                                                                                     SafeRandom random,
                                                                                     Fragmenter fragmenter,
                                                                                     boolean saveCache) {
        Function<Set<FileWrapper>, List<FileWrapper>> readOnlySharingFilesFilter = files ->
                files.stream().filter(f -> isReadCapFile(f.getName()))
                        .sorted(readOnlyCapabilitiesInIndexOrder)
                        .collect(Collectors.toList());

        return loadSharingLinks( homeDirSupplier, friendSharedDir,
                friendName, network, random,
                fragmenter, saveCache, readOnlySharingFilesFilter, CAPABILITY_READ, READ_CAPABILITY_SIZE, SHARING_READ_FILE_MAX_SIZE);
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
    public static CompletableFuture<CapabilitiesFromUser> loadWriteAccessSharingLinks(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                      FileWrapper friendSharedDir,
                                                                                      String friendName,
                                                                                      NetworkAccess network,
                                                                                      SafeRandom random,
                                                                                      Fragmenter fragmenter,
                                                                                      boolean saveCache) {
        Function<Set<FileWrapper>, List<FileWrapper>> editSharingFilesFilter = files ->
                files.stream().filter(f -> isEditCapFile(f.getName()))
                        .sorted(editCapabilitiesInIndexOrder)
                        .collect(Collectors.toList());

        return loadSharingLinks( homeDirSupplier, friendSharedDir,
                friendName, network, random,
                fragmenter, saveCache, editSharingFilesFilter, CAPABILITY_EDIT, EDIT_CAPABILITY_SIZE, SHARING_EDIT_FILE_MAX_SIZE);
    }

    private static CompletableFuture<CapabilitiesFromUser> loadSharingLinks(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                            FileWrapper friendSharedDir,
                                                                            String friendName,
                                                                            NetworkAccess network,
                                                                            SafeRandom random,
                                                                            Fragmenter fragmenter,
                                                                            boolean saveCache,
                                                                            Function<Set<FileWrapper>, List<FileWrapper>> sharingFileFilter,
                                                                            String capabilityType,
                                                                            int capabilitySize,
                                                                            int capabilityFileMaxSize) {
        return friendSharedDir.getChildren(network)
                .thenCompose(files -> {
                    List<FileWrapper> sharingFiles = sharingFileFilter.apply(files);
                    return getSharingCacheFile(friendName, homeDirSupplier, network, random, capabilityType).thenCompose(optCachedFile -> {
                        long totalRecords = sharingFiles.stream().mapToLong(f -> f.getFileProperties().size).sum() / capabilitySize;
                        if(! optCachedFile.isPresent() ) {
                            CompletableFuture<List<CapabilityWithPath>> allFiles = Futures.reduceAll(sharingFiles,
                                    Collections.emptyList(),
                                    (res, sharingFile) -> readSharingFile(friendSharedDir.getName(), capabilitySize, friendSharedDir.owner(), sharingFile, network, random)
                                            .thenApply(retrievedCaps -> Stream.concat(res.stream(), retrievedCaps.stream()).collect(Collectors.toList())),
                                    (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList())
                            );
                            return allFiles.thenCompose(res -> {
                                if(saveCache && res.size() > 0) {
                                    return saveRetrievedCapabilityCache(totalRecords, homeDirSupplier, friendName,
                                            network, random, fragmenter, res, capabilityType);
                                } else {
                                    return CompletableFuture.completedFuture(new CapabilitiesFromUser(totalRecords, res));
                                }
                            });
                        } else {
                            FileWrapper cachedFile = optCachedFile.get();
                            return readRetrievedCapabilityCache(cachedFile, network, random).thenCompose(cache -> {
                                if (totalRecords == cache.getRecordsRead())
                                    return CompletableFuture.completedFuture(cache);
                                int shareFileIndex = (int)(cache.getRecordsRead() * capabilitySize) / capabilityFileMaxSize;
                                int recordIndex = (int) ((cache.getRecordsRead() * capabilitySize) % capabilityFileMaxSize) / capabilitySize;
                                List<FileWrapper> sharingFilesToRead = sharingFiles.subList(shareFileIndex, sharingFiles.size());
                                CompletableFuture<List<CapabilityWithPath>> allFiles = Futures.reduceAll(sharingFilesToRead.subList(0, sharingFilesToRead.size() - 1),
                                        Collections.emptyList(),
                                        (res, sharingFile) -> readSharingFile(friendSharedDir.getName(), capabilitySize, friendSharedDir.owner(), sharingFile, network, random)
                                                .thenApply(retrievedCaps -> Stream.concat(res.stream(), retrievedCaps.stream()).collect(Collectors.toList())),
                                        (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList()));
                                return allFiles
                                        .thenCompose(res -> readSharingFile(recordIndex, capabilitySize, friendSharedDir.getName(),
                                                friendSharedDir.owner(), sharingFilesToRead.get(sharingFilesToRead.size() -1), network, random))
                                        .thenCompose(res -> {
                                            if (saveCache) {
                                                return saveRetrievedCapabilityCache(totalRecords, homeDirSupplier, friendName,
                                                        network, random, fragmenter, res, capabilityType);
                                            } else {
                                                return CompletableFuture.completedFuture(new CapabilitiesFromUser(totalRecords, res));
                                            }
                                        });
                            });
                        }
                    });
                });
    }


    public static CompletableFuture<CapabilitiesFromUser> loadReadAccessSharingLinksFromIndex(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                              FileWrapper friendSharedDir,
                                                                                              String friendName,
                                                                                              NetworkAccess network,
                                                                                              SafeRandom random,
                                                                                              Fragmenter fragmenter,
                                                                                              long capIndex,
                                                                                              boolean saveCache) {
        Function<Set<FileWrapper>, List<FileWrapper>> readOnlySharingFilesFilter = files ->
                files.stream().filter(f -> isReadCapFile(f.getName()))
                        .sorted(readOnlyCapabilitiesInIndexOrder)
                        .collect(Collectors.toList());

        return loadSharingLinksFromIndex( homeDirSupplier,
                friendSharedDir, friendName, network, random, fragmenter, capIndex, saveCache, readOnlySharingFilesFilter,
                CAPABILITY_READ, READ_CAPABILITY_SIZE, SHARING_READ_FILE_MAX_SIZE);

    }

    public static CompletableFuture<CapabilitiesFromUser> loadWriteAccessSharingLinksFromIndex(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                               FileWrapper friendSharedDir,
                                                                                               String friendName,
                                                                                               NetworkAccess network,
                                                                                               SafeRandom random,
                                                                                               Fragmenter fragmenter,
                                                                                               long capIndex,
                                                                                               boolean saveCache) {
        Function<Set<FileWrapper>, List<FileWrapper>> editSharingFilesFilter = files ->
                files.stream().filter(f -> isEditCapFile(f.getName()))
                        .sorted(editCapabilitiesInIndexOrder)
                        .collect(Collectors.toList());

        return loadSharingLinksFromIndex( homeDirSupplier,
                friendSharedDir, friendName, network, random, fragmenter, capIndex, saveCache, editSharingFilesFilter,
                CAPABILITY_EDIT, EDIT_CAPABILITY_SIZE, SHARING_EDIT_FILE_MAX_SIZE);
    }

    private static CompletableFuture<CapabilitiesFromUser> loadSharingLinksFromIndex(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                     FileWrapper friendSharedDir,
                                                                                     String friendName,
                                                                                     NetworkAccess network,
                                                                                     SafeRandom random,
                                                                                     Fragmenter fragmenter,
                                                                                     long capIndex,
                                                                                     boolean saveCache,
                                                                                     Function<Set<FileWrapper>, List<FileWrapper>> sharingFileFilter,
                                                                                     String capabilityType,
                                                                                     int capabilitySize,
                                                                                     int capabilityFileMaxSize) {
        return friendSharedDir.getChildren(network)
                .thenCompose(files -> {
                    List<FileWrapper> sharingFiles = sharingFileFilter.apply(files);
                    long totalRecords = sharingFiles.stream().mapToLong(f -> f.getFileProperties().size).sum() / capabilitySize;
                    int shareFileIndex = (int) (capIndex * capabilitySize) / capabilityFileMaxSize;
                    int recordIndex = (int) (capIndex % CAPS_PER_FILE);
                    List<FileWrapper> sharingFilesToRead = sharingFiles.subList(shareFileIndex, sharingFiles.size());

                    CompletableFuture<List<CapabilityWithPath>> allFiles = Futures.reduceAll(sharingFilesToRead.subList(0, sharingFilesToRead.size() - 1),
                            Collections.emptyList(),
                            (res, sharingFile) -> readSharingFile(friendSharedDir.getName(), capabilitySize, friendSharedDir.owner(), sharingFile, network, random)
                                    .thenApply(retrievedCaps -> Stream.concat(res.stream(), retrievedCaps.stream()).collect(Collectors.toList())),
                            (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList()));
                    CompletableFuture<CapabilitiesFromUser> result = allFiles
                            .thenCompose(res -> readSharingFile(recordIndex, capabilitySize, friendSharedDir.getName(),
                                    friendSharedDir.owner(), sharingFilesToRead.get(sharingFilesToRead.size() - 1), network, random))
                            .thenCompose(res -> {
                                if (saveCache) {
                                    return saveRetrievedCapabilityCache(totalRecords - capIndex, homeDirSupplier, friendName,
                                            network, random, fragmenter, res, capabilityType);
                                } else {
                                    return CompletableFuture.completedFuture(new CapabilitiesFromUser(totalRecords - capIndex, res));
                                }
                            });
                    return result;
                });
    }

    public static CompletableFuture<Long> getReadOnlyCapabilityCount(FileWrapper friendSharedDir,
                                                                     NetworkAccess network) {
        return getCapabilityCount(READ_SHARING_FILE_PREFIX, friendSharedDir, network);
    }

    public static CompletableFuture<Long> getEditableCapabilityCount(FileWrapper friendSharedDir,
                                                                     NetworkAccess network) {
        return getCapabilityCount(EDIT_SHARING_FILE_PREFIX, friendSharedDir, network);
    }

    private static CompletableFuture<Long> getCapabilityCount(String filenamePrefix, FileWrapper friendSharedDir,
                                                              NetworkAccess network) {
        return friendSharedDir.getChildren(network)
                .thenApply(capFiles -> capFiles.stream().filter(f -> f.getName().startsWith(filenamePrefix)).mapToLong(f -> f.getFileProperties().size).sum() / READ_CAPABILITY_SIZE);
    }

    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(String ownerName,
                                                                              int capabilitySize,
                                                                              PublicKeyHash owner,
                                                                              FileWrapper file,
                                                                              NetworkAccess network,
                                                                              SafeRandom random) {
        return readSharingFile(0, capabilitySize, ownerName, owner, file, network, random);
    }

    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(int offsetIndex,
                                                                              int capabilitySize,
                                                                              String ownerName,
                                                                              PublicKeyHash owner,
                                                                              FileWrapper file,
                                                                              NetworkAccess network,
                                                                              SafeRandom random) {
        return file.getInputStream(network, random, x -> {}).thenCompose(reader -> {
            int currentFileSize = (int) file.getSize();
            List<CompletableFuture<Optional<CapabilityWithPath>>> capabilities = IntStream.range(offsetIndex, currentFileSize / capabilitySize)
                    .mapToObj(e -> e * capabilitySize)
                    .map(offset -> readSharingRecord(ownerName, owner, reader, offset, network))
                    .collect(Collectors.toList());

            return Futures.combineAllInOrder(capabilities).thenApply(optList -> optList.stream()
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList()));
        });
    }

    private static CompletableFuture<Optional<CapabilityWithPath>> readSharingRecord(String ownerName,
                                                                                     PublicKeyHash owner,
                                                                                     AsyncReader reader,
                                                                                     int offset,
                                                                                     NetworkAccess network) {
        byte[] serialisedFilePointer = new byte[READ_CAPABILITY_SIZE];
        return reader.seek( 0, offset).thenCompose( currentPos ->
                currentPos.readIntoArray(serialisedFilePointer, 0, READ_CAPABILITY_SIZE)
                        .thenCompose(bytesRead -> {
                            AbsoluteCapability pointer = AbsoluteCapability.fromCbor(CborObject.fromByteArray(serialisedFilePointer));
                            EntryPoint entry = new EntryPoint(pointer, ownerName);
                            return network.retrieveEntryPoint(entry).thenCompose( optFTN -> {
                                if(optFTN.isPresent()) {
                                    FileWrapper ftn = optFTN.get();
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

    private static CompletableFuture<Optional<FileWrapper>> getSharingCacheFile(String friendName,
                                                                         Supplier<CompletableFuture<FileWrapper>> getHome,
                                                                         NetworkAccess network,
                                                                         SafeRandom random,
                                                                        String capabilityType) {
        return getCapabilityCacheDir(getHome, network, random)
                .thenCompose(cacheDir -> cacheDir.getChild(friendName + capabilityType, network));
    }

    private static CompletableFuture<FileWrapper> getCapabilityCacheDir(Supplier<CompletableFuture<FileWrapper>> getHome,
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
                                                                                       Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                       String friendName,
                                                                                       NetworkAccess network,
                                                                                       SafeRandom random,
                                                                                       Fragmenter fragmenter,
                                                                                       List<CapabilityWithPath> retrievedCapabilities,
                                                                                       String capabilityType) {
        CapabilitiesFromUser capabilitiesFromUser = new CapabilitiesFromUser(recordsRead, retrievedCapabilities);
        byte[] data = capabilitiesFromUser.serialize();
        AsyncReader.ArrayBacked dataReader = new AsyncReader.ArrayBacked(data);
        return getCapabilityCacheDir(homeDirSupplier, network, random)
                .thenCompose(cacheDir -> cacheDir.uploadFile(friendName + capabilityType, dataReader, true, (long) data.length,
                true, network, random, x-> {}, fragmenter).thenApply(x -> capabilitiesFromUser));
    }

    private static CompletableFuture<CapabilitiesFromUser> readRetrievedCapabilityCache(FileWrapper cacheFile, NetworkAccess network, SafeRandom random) {
        return cacheFile.getInputStream(network, random, x -> { })
                .thenCompose(reader -> {
                    byte[] storeData = new byte[(int) cacheFile.getSize()];
                    return reader.readIntoArray(storeData, 0, storeData.length)
                            .thenApply(x -> CapabilitiesFromUser.fromCbor(CborObject.fromByteArray(storeData)));
                });
    }
}
