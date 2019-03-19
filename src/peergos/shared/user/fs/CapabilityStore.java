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
    private static final String CAPABILITY_CACHE_DIR = ".capabilitycache";
    private static final String READ_SHARING_FILE_NAME = "sharing.r";
    private static final String EDIT_SHARING_FILE_NAME = "sharing.w";

    public static CompletableFuture<FileWrapper> addReadOnlySharingLinkTo(FileWrapper sharedDir,
                                                                          AbsoluteCapability capability,
                                                                          NetworkAccess network,
                                                                          SafeRandom random,
                                                                          Hasher hasher) {
        return addSharingLinkTo(sharedDir, capability.readOnly(), network, random, hasher, CapabilityStore.READ_SHARING_FILE_NAME);
    }

    public static CompletableFuture<FileWrapper> addEditSharingLinkTo(FileWrapper sharedDir,
                                                                      WritableAbsoluteCapability capability,
                                                                      NetworkAccess network,
                                                                      SafeRandom random,
                                                                      Hasher hasher) {
        return addSharingLinkTo(sharedDir, capability, network, random, hasher, CapabilityStore.EDIT_SHARING_FILE_NAME);
    }

    private static CompletableFuture<FileWrapper> addSharingLinkTo(FileWrapper sharedDir,
                                                                  AbsoluteCapability capability,
                                                                  NetworkAccess network,
                                                                  SafeRandom random,
                                                                  Hasher hasher,
                                                                  String capStoreFilename) {
        if (! sharedDir.isDirectory() || ! sharedDir.isWritable()) {
            CompletableFuture<FileWrapper> error = new CompletableFuture<>();
            error.completeExceptionally(new IllegalArgumentException("Can only add link to a writable directory!"));
            return error;
        }

        return sharedDir.getChild(capStoreFilename, network)
                .thenCompose(capStore -> {
                    byte[] serializedCapability = capability.toCbor().toByteArray();
                    AsyncReader.ArrayBacked newCapability = new AsyncReader.ArrayBacked(serializedCapability);
                    long startIndex = capStore.map(f -> f.getSize()).orElse(0L);
                    return sharedDir.uploadFileSection(capStoreFilename, newCapability, false,
                            startIndex, startIndex + serializedCapability.length, Optional.empty(), true,
                            network, random, hasher, x -> {}, sharedDir.generateChildLocations(1, random));
                });
    }

    /**
     *
     * @param homeDirSupplier
     * @param friendSharedDir
     * @param friendName
     * @param network
     * @param random
     * @param saveCache
     * @return a pair of the current capability index, and the valid capabilities
     */
    public static CompletableFuture<CapabilitiesFromUser> loadReadAccessSharingLinks(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                     FileWrapper friendSharedDir,
                                                                                     String friendName,
                                                                                     NetworkAccess network,
                                                                                     SafeRandom random,
                                                                                     Hasher hasher,
                                                                                     boolean saveCache) {
        return loadSharingLinks( homeDirSupplier, friendSharedDir, friendName, network, random, hasher,
                saveCache, READ_SHARING_FILE_NAME);
    }

    /**
     *
     * @param homeDirSupplier
     * @param friendSharedDir
     * @param friendName
     * @param network
     * @param random
     * @param saveCache
     * @return a pair of the current capability index, and the valid capabilities
     */
    public static CompletableFuture<CapabilitiesFromUser> loadWriteAccessSharingLinks(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                      FileWrapper friendSharedDir,
                                                                                      String friendName,
                                                                                      NetworkAccess network,
                                                                                      SafeRandom random,
                                                                                      Hasher hasher,
                                                                                      boolean saveCache) {

        return loadSharingLinks( homeDirSupplier, friendSharedDir, friendName, network, random, hasher,
                saveCache, EDIT_SHARING_FILE_NAME);
    }

    private static CompletableFuture<CapabilitiesFromUser> loadSharingLinks(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                            FileWrapper friendSharedDir,
                                                                            String friendName,
                                                                            NetworkAccess network,
                                                                            SafeRandom random,
                                                                            Hasher hasher,
                                                                            boolean saveCache,
                                                                            String capStoreFilename) {
        return friendSharedDir.getChild(capStoreFilename, network)
                .thenCompose(capFile -> {
                    if (! capFile.isPresent())
                        return CompletableFuture.completedFuture(new CapabilitiesFromUser(0, Collections.emptyList()));
                    long capFilesize = capFile.get().getSize();
                    return getSharingCacheFile(friendName, homeDirSupplier, network, random, hasher, capStoreFilename).thenCompose(optCachedFile -> {
                        if(! optCachedFile.isPresent()) {
                            return readSharingFile(friendSharedDir.getName(), friendSharedDir.owner(), capFile.get(), network, random)
                                    .thenCompose(res -> {
                                        if(saveCache && res.size() > 0) {
                                            return saveRetrievedCapabilityCache(capFilesize, homeDirSupplier, friendName,
                                                    network, random, hasher, res, capStoreFilename);
                                        } else {
                                            return CompletableFuture.completedFuture(new CapabilitiesFromUser(capFilesize, res));
                                        }
                                    });
                        } else {
                            FileWrapper cachedFile = optCachedFile.get();
                            return readRetrievedCapabilityCache(cachedFile, network, random).thenCompose(cache -> {
                                if (capFilesize == cache.getBytesRead())
                                    return CompletableFuture.completedFuture(cache);
                                return readSharingFile(cache.getBytesRead(), friendSharedDir.getName(),
                                        friendSharedDir.owner(), capFile.get(), network, random)
                                        .thenCompose(res -> {
                                            if (saveCache) {
                                                return saveRetrievedCapabilityCache(capFilesize, homeDirSupplier, friendName,
                                                        network, random, hasher, res, capStoreFilename);
                                            } else {
                                                return CompletableFuture.completedFuture(new CapabilitiesFromUser(capFilesize, res));
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
                                                                                              Hasher hasher,
                                                                                              long startOffset,
                                                                                              boolean saveCache) {

        return loadSharingLinksFromIndex(homeDirSupplier, friendSharedDir, friendName, network, random, hasher,
                startOffset, saveCache, READ_SHARING_FILE_NAME);
    }

    public static CompletableFuture<CapabilitiesFromUser> loadWriteAccessSharingLinksFromIndex(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                               FileWrapper friendSharedDir,
                                                                                               String friendName,
                                                                                               NetworkAccess network,
                                                                                               SafeRandom random,
                                                                                               Hasher hasher,
                                                                                               long startOffset,
                                                                                               boolean saveCache) {

        return loadSharingLinksFromIndex(homeDirSupplier, friendSharedDir, friendName, network, random, hasher,
                startOffset, saveCache, EDIT_SHARING_FILE_NAME);
    }

    private static CompletableFuture<CapabilitiesFromUser> loadSharingLinksFromIndex(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                     FileWrapper friendSharedDir,
                                                                                     String friendName,
                                                                                     NetworkAccess network,
                                                                                     SafeRandom random,
                                                                                     Hasher hasher,
                                                                                     long startOffset,
                                                                                     boolean saveCache,
                                                                                     String capFilename) {
        return friendSharedDir.getChild(capFilename, network)
                .thenCompose(file -> {
                    if (! file.isPresent())
                        return CompletableFuture.completedFuture(new CapabilitiesFromUser(0, Collections.emptyList()));
                    long capFileSize = file.get().getSize();
                    return readSharingFile(startOffset, friendSharedDir.getName(), friendSharedDir.owner(), file.get(), network, random)
                            .thenCompose(res -> {
                                if (saveCache) {
                                    return saveRetrievedCapabilityCache(capFileSize - startOffset, homeDirSupplier, friendName,
                                            network, random, hasher, res, capFilename);
                                } else {
                                    return CompletableFuture.completedFuture(new CapabilitiesFromUser(capFileSize - startOffset, res));
                                }
                            });
                });
    }

    public static CompletableFuture<Long> getReadOnlyCapabilityFileSize(FileWrapper friendSharedDir,
                                                                        NetworkAccess network) {
        return getCapabilityFileSize(READ_SHARING_FILE_NAME, friendSharedDir, network);
    }

    public static CompletableFuture<Long> getEditableCapabilityFileSize(FileWrapper friendSharedDir,
                                                                        NetworkAccess network) {
        return getCapabilityFileSize(EDIT_SHARING_FILE_NAME, friendSharedDir, network);
    }

    private static CompletableFuture<Long> getCapabilityFileSize(String filename, FileWrapper friendSharedDir,
                                                                 NetworkAccess network) {
        return friendSharedDir.getChild(filename, network)
                .thenApply(capFile -> capFile.map(f -> f.getFileProperties().size).orElse(0L));
    }

    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(String ownerName,
                                                                              PublicKeyHash owner,
                                                                              FileWrapper file,
                                                                              NetworkAccess network,
                                                                              SafeRandom random) {
        return readSharingFile(0, ownerName, owner, file, network, random);
    }

    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(long startOffset,
                                                                              String ownerName,
                                                                              PublicKeyHash owner,
                                                                              FileWrapper file,
                                                                              NetworkAccess network,
                                                                              SafeRandom random) {
        return file.getInputStream(network, random, x -> {})
                .thenCompose(reader -> reader.seek(startOffset))
                .thenCompose(seeked -> readSharingRecords(ownerName, owner, seeked, file.getSize() - startOffset, network));
    }

    private static CompletableFuture<List<CapabilityWithPath>> readSharingRecords(String ownerName,
                                                                                  PublicKeyHash owner,
                                                                                  AsyncReader reader,
                                                                                  long maxBytesToRead,
                                                                                  NetworkAccess network) {
        if (maxBytesToRead == 0)
            return CompletableFuture.completedFuture(Collections.emptyList());

        List<AbsoluteCapability> caps = new ArrayList<>();
        return reader.parseStream(AbsoluteCapability::fromCbor, caps::add, maxBytesToRead)
                .thenCompose(bytesRead -> {
                    return Futures.combineAllInOrder(caps.stream().map(pointer -> {
                        EntryPoint entry = new EntryPoint(pointer, ownerName);
                        return network.retrieveEntryPoint(entry).thenCompose(fileOpt -> {
                            if (fileOpt.isPresent()) {
                                try {
                                    CompletableFuture<List<CapabilityWithPath>> res = fileOpt.get().getPath(network)
                                            .thenApply(path -> Collections.singletonList(new CapabilityWithPath(path, pointer)));
                                    return res;
                                } catch (NoSuchElementException nsee) {
                                    return Futures.errored(nsee); //a file ancestor no longer exists!?
                                }
                            } else {
                                return CompletableFuture.completedFuture(Collections.emptyList());
                            }
                        }).exceptionally(t -> Collections.emptyList());
                    }).collect(Collectors.toList()))
                            .thenApply(res -> res.stream().flatMap(x -> x.stream()).collect(Collectors.toList()))
                            .thenCompose(results -> readSharingRecords(ownerName, owner, reader,
                                    maxBytesToRead - bytesRead, network)
                                    .thenApply(recurse -> Stream.concat(results.stream(), recurse.stream())
                                            .collect(Collectors.toList())));
                });
    }

    private static CompletableFuture<Optional<FileWrapper>> getSharingCacheFile(String friendName,
                                                                                Supplier<CompletableFuture<FileWrapper>> getHome,
                                                                                NetworkAccess network,
                                                                                SafeRandom random,
                                                                                Hasher hasher,
                                                                                String capabilityType) {
        return getCapabilityCacheDir(getHome, network, random, hasher)
                .thenCompose(cacheDir -> cacheDir.getChild(friendName + capabilityType, network));
    }

    private static CompletableFuture<FileWrapper> getCapabilityCacheDir(Supplier<CompletableFuture<FileWrapper>> getHome,
                                                                        NetworkAccess network,
                                                                        SafeRandom random,
                                                                        Hasher hasher) {
        return getHome.get()
                .thenCompose(home -> home.getChild(CAPABILITY_CACHE_DIR, network)
                        .thenCompose(opt ->
                                opt.map(CompletableFuture::completedFuture)
                                        .orElseGet(() -> home.mkdir(CAPABILITY_CACHE_DIR, network, true, random, hasher)
                                                .thenCompose(x -> getCapabilityCacheDir(getHome, network, random, hasher)))));
    }

    public static CompletableFuture<CapabilitiesFromUser> saveRetrievedCapabilityCache(long recordsRead,
                                                                                       Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                                       String friendName,
                                                                                       NetworkAccess network,
                                                                                       SafeRandom random,
                                                                                       Hasher hasher,
                                                                                       List<CapabilityWithPath> retrievedCapabilities,
                                                                                       String capabilityType) {
        CapabilitiesFromUser capabilitiesFromUser = new CapabilitiesFromUser(recordsRead, retrievedCapabilities);
        byte[] data = capabilitiesFromUser.serialize();
        AsyncReader.ArrayBacked dataReader = new AsyncReader.ArrayBacked(data);
        return getCapabilityCacheDir(homeDirSupplier, network, random, hasher)
                .thenCompose(cacheDir -> cacheDir.uploadOrOverwriteFile(friendName + capabilityType, dataReader,
                        (long) data.length, network, random, hasher, x-> {},
                        cacheDir.generateChildLocationsFromSize(data.length, random))
                        .thenApply(x -> capabilitiesFromUser));
    }

    private static CompletableFuture<CapabilitiesFromUser> readRetrievedCapabilityCache(FileWrapper cacheFile,
                                                                                        NetworkAccess network,
                                                                                        SafeRandom random) {
        return cacheFile.getInputStream(network, random, x -> { })
                .thenCompose(reader -> {
                    byte[] storeData = new byte[(int) cacheFile.getSize()];
                    return reader.readIntoArray(storeData, 0, storeData.length)
                            .thenApply(x -> CapabilitiesFromUser.fromCbor(CborObject.fromByteArray(storeData)));
                });
    }
}
