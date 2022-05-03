package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
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
 * file. Knowing the index of the capability in the overall list you can calculate the file name, and the offset in the
 * file at which the capability is stored. Write and read capabilities form logically separate append only lists.
 */
public class CapabilityStore {
    public static final String CAPABILITY_CACHE_DIR = ".capabilitycache";
    private static final String READ_SHARING_FILE_NAME = "sharing.r";
    private static final String EDIT_SHARING_FILE_NAME = "sharing.w";

    public static CompletableFuture<Snapshot> addReadOnlySharingLinkTo(FileWrapper sharedDir,
                                                                       AbsoluteCapability capability,
                                                                       Committer c,
                                                                       NetworkAccess network,
                                                                       Crypto crypto) {
        return addSharingLinkTo(sharedDir, capability.readOnly(), network, crypto, CapabilityStore.READ_SHARING_FILE_NAME, c);
    }

    public static CompletableFuture<Snapshot> addEditSharingLinkTo(FileWrapper sharedDir,
                                                                   WritableAbsoluteCapability capability,
                                                                   Committer c,
                                                                   NetworkAccess network,
                                                                   Crypto crypto) {
        return addSharingLinkTo(sharedDir, capability, network, crypto, CapabilityStore.EDIT_SHARING_FILE_NAME, c);
    }

    private static CompletableFuture<Snapshot> addSharingLinkTo(FileWrapper sharedDir,
                                                                AbsoluteCapability capability,
                                                                NetworkAccess network,
                                                                Crypto crypto,
                                                                String capStoreFilename,
                                                                Committer c) {
        if (! sharedDir.isDirectory() || ! sharedDir.isWritable()) {
            return Futures.errored(new IllegalArgumentException("Can only add link to a writable directory!"));
        }

        return sharedDir.getChild(capStoreFilename, crypto.hasher, network)
                .thenCompose(capStore -> {
                    byte[] serializedCapability = capability.toCbor().toByteArray();
                    AsyncReader.ArrayBacked newCapability = new AsyncReader.ArrayBacked(serializedCapability);
                    long startIndex = capStore.map(f -> f.getSize()).orElse(0L);
                    return sharedDir.uploadFileSection(sharedDir.version, c, capStoreFilename, newCapability, false,
                            startIndex, startIndex + serializedCapability.length, Optional.empty(), false, true,
                            false, network, crypto, x -> {}, crypto.random.randomBytes(32),
                            Optional.empty(), Optional.of(Bat.random(crypto.random)), sharedDir.mirrorBatId());
                });
    }

    /**
     *
     * @param cacheDir
     * @param friendSharedDir
     * @param friendName
     * @param network
     * @param crypto
     * @return the current byte index, and the valid capabilities
     */
    public static CompletableFuture<CapabilitiesFromUser> loadReadOnlyLinks(FileWrapper cacheDir,
                                                                            FileWrapper friendSharedDir,
                                                                            String friendName,
                                                                            NetworkAccess network,
                                                                            Crypto crypto,
                                                                            boolean inbound) {
        return loadSharingLinks(cacheDir, friendSharedDir, friendName, network, crypto,
                inbound, READ_SHARING_FILE_NAME);
    }

    /**
     *
     * @param cacheDir
     * @param friendName
     * @param network
     * @param crypto
     * @return the current byte index, and the valid capabilities
     */
    public static CompletableFuture<CapabilitiesFromUser> loadCachedReadOnlyLinks(FileWrapper cacheDir,
                                                                                  String friendName,
                                                                                  NetworkAccess network,
                                                                                  Crypto crypto) {
        return loadSharingLinksCache(cacheDir, friendName, network, crypto, cacheFilename(true, READ_SHARING_FILE_NAME));
    }

    /**
     *
     * @param cacheDir
     * @param friendSharedDir
     * @param friendName
     * @param network
     * @param crypto
     * @return the current byte index, and the valid capabilities
     */
    public static CompletableFuture<CapabilitiesFromUser> loadWriteableLinks(FileWrapper cacheDir,
                                                                             FileWrapper friendSharedDir,
                                                                             String friendName,
                                                                             NetworkAccess network,
                                                                             Crypto crypto,
                                                                             boolean inbound) {

        return loadSharingLinks(cacheDir, friendSharedDir, friendName, network, crypto,
                inbound, EDIT_SHARING_FILE_NAME);
    }

    /**
     *
     * @param cacheDir
     * @param friendName
     * @param network
     * @param crypto
     * @return the current byte index, and the valid capabilities
     */
    public static CompletableFuture<CapabilitiesFromUser> loadCachedWriteableLinks(FileWrapper cacheDir,
                                                                                   String friendName,
                                                                                   NetworkAccess network,
                                                                                   Crypto crypto) {

        return loadSharingLinksCache(cacheDir, friendName, network, crypto, cacheFilename(true, EDIT_SHARING_FILE_NAME));
    }

    private static CompletableFuture<CapabilitiesFromUser> loadSharingLinks(FileWrapper cacheDir,
                                                                            FileWrapper friendSharedDir,
                                                                            String friendName,
                                                                            NetworkAccess network,
                                                                            Crypto crypto,
                                                                            boolean inbound,
                                                                            String capStoreFilename) {
        return friendSharedDir.getChild(capStoreFilename, crypto.hasher, network)
                .thenCompose(capFile -> {
                    if (! capFile.isPresent())
                        return CompletableFuture.completedFuture(new CapabilitiesFromUser(0, Collections.emptyList()));
                    long capFilesize = capFile.get().getSize();
                    String cacheFilenameSuffix = cacheFilename(inbound, capStoreFilename);
                    return getSharingCacheFile(friendName, cacheDir, network, crypto, cacheFilenameSuffix).thenCompose(optCachedFile -> {
                        if (! optCachedFile.isPresent()) {
                            return readSharingFile(friendSharedDir.getName(), friendSharedDir.owner(), capFile.get(), network, crypto)
                                    .thenCompose(res -> Futures.of(new CapabilitiesFromUser(capFilesize, res)));
                        } else {
                            FileWrapper cachedFile = optCachedFile.get();
                            return readRetrievedCapabilityCache(cachedFile, network, crypto).thenCompose(cache -> {
                                if (capFilesize == cache.getBytesRead())
                                    return CompletableFuture.completedFuture(cache);
                                return readSharingFile(cache.getBytesRead(), friendSharedDir.getName(),
                                        friendSharedDir.owner(), capFile.get(), network, crypto)
                                        .thenCompose(res -> Futures.of(new CapabilitiesFromUser(capFilesize, res)));
                            });
                        }
                    });
                });
    }


    private static CompletableFuture<CapabilitiesFromUser> loadSharingLinksCache(FileWrapper cacheDir,
                                                                                 String friendName,
                                                                                 NetworkAccess network,
                                                                                 Crypto crypto,
                                                                                 String capStoreFilename) {
        return getSharingCacheFile(friendName, cacheDir, network, crypto, capStoreFilename)
                .thenCompose(optCachedFile -> {
                    if(! optCachedFile.isPresent()) {
                        return CompletableFuture.completedFuture(new CapabilitiesFromUser(0, Collections.emptyList()));
                    } else {
                        FileWrapper cachedFile = optCachedFile.get();
                        return readRetrievedCapabilityCache(cachedFile, network, crypto);
                    }
                });
    }


    public static CompletableFuture<CapabilitiesFromUser> loadReadAccessSharingLinksFromIndex(FileWrapper cacheDir,
                                                                                              FileWrapper friendSharedDir,
                                                                                              String friendName,
                                                                                              NetworkAccess network,
                                                                                              Crypto crypto,
                                                                                              long startOffset,
                                                                                              boolean saveCache,
                                                                                              boolean inbound) {

        return loadSharingLinksFromIndex(cacheDir, friendSharedDir, friendName, network, crypto,
                startOffset, saveCache, inbound, READ_SHARING_FILE_NAME);
    }

    public static CompletableFuture<CapabilitiesFromUser> loadWriteAccessSharingLinksFromIndex(FileWrapper cacheDir,
                                                                                               FileWrapper friendSharedDir,
                                                                                               String friendName,
                                                                                               NetworkAccess network,
                                                                                               Crypto crypto,
                                                                                               long startOffset,
                                                                                               boolean saveCache,
                                                                                               boolean inbound) {

        return loadSharingLinksFromIndex(cacheDir, friendSharedDir, friendName, network, crypto,
                startOffset, saveCache, inbound, EDIT_SHARING_FILE_NAME);
    }

    private static CompletableFuture<CapabilitiesFromUser> loadSharingLinksFromIndex(FileWrapper cacheDir,
                                                                                     FileWrapper friendSharedDir,
                                                                                     String friendName,
                                                                                     NetworkAccess network,
                                                                                     Crypto crypto,
                                                                                     long startOffset,
                                                                                     boolean saveCache,
                                                                                     boolean inbound,
                                                                                     String capFilename) {
        return friendSharedDir.getChild(capFilename, crypto.hasher, network)
                .thenCompose(file -> {
                    if (! file.isPresent())
                        return Futures.of(CapabilitiesFromUser.empty());
                    long capFileSize = file.get().getSize();
                    if (capFileSize == startOffset)
                        return Futures.of(CapabilitiesFromUser.empty());
                    return readSharingFile(startOffset, friendSharedDir.getName(), friendSharedDir.owner(), file.get(), network, crypto)
                            .thenCompose(res -> Futures.of(new CapabilitiesFromUser(capFileSize - startOffset, res)));
                });
    }

    private static String cacheFilename(boolean inbound, String suffix) {
        return (inbound ? "-in-" : "-out-") + suffix;
    }

    public static CompletableFuture<Long> getReadOnlyCapabilityFileSize(FileWrapper friendSharedDir,
                                                                        Crypto crypto,
                                                                        NetworkAccess network) {
        return getCapabilityFileSize(READ_SHARING_FILE_NAME, friendSharedDir, crypto, network);
    }

    public static CompletableFuture<Long> getEditableCapabilityFileSize(FileWrapper friendSharedDir,
                                                                        Crypto crypto,
                                                                        NetworkAccess network) {
        return getCapabilityFileSize(EDIT_SHARING_FILE_NAME, friendSharedDir, crypto, network);
    }

    private static CompletableFuture<Long> getCapabilityFileSize(String filename,
                                                                 FileWrapper friendSharedDir,
                                                                 Crypto crypto,
                                                                 NetworkAccess network) {
        return friendSharedDir.getChild(filename, crypto.hasher, network)
                .thenApply(capFile -> capFile.map(f -> f.getFileProperties().size).orElse(0L));
    }

    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(String ownerName,
                                                                              PublicKeyHash owner,
                                                                              FileWrapper file,
                                                                              NetworkAccess network,
                                                                              Crypto crypto) {
        return readSharingFile(0, ownerName, owner, file, network, crypto);
    }

    public static CompletableFuture<List<CapabilityWithPath>> readSharingFile(long startOffset,
                                                                              String ownerName,
                                                                              PublicKeyHash owner,
                                                                              FileWrapper file,
                                                                              NetworkAccess network,
                                                                              Crypto crypto) {
        return file.getInputStream(network, crypto, x -> {})
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
                                return CompletableFuture.completedFuture(Collections.<CapabilityWithPath>emptyList());
                            }
                        }).exceptionally(t -> Collections.<CapabilityWithPath>emptyList());
                    }).collect(Collectors.toList()))
                            .thenApply(res -> res.stream().flatMap(x -> x.stream()).collect(Collectors.toList()))
                            .thenCompose(results -> readSharingRecords(ownerName, owner, reader,
                                    maxBytesToRead - bytesRead, network)
                                    .thenApply(recurse -> Stream.concat(results.stream(), recurse.stream())
                                            .collect(Collectors.toList())));
                });
    }

    private static CompletableFuture<Optional<FileWrapper>> getSharingCacheFile(String friendName,
                                                                                FileWrapper cacheDir,
                                                                                NetworkAccess network,
                                                                                Crypto crypto,
                                                                                String filenameSuffix) {
        return cacheDir.getUpdated(network)
                .thenCompose(updated -> updated.getChild(friendName + filenameSuffix, crypto.hasher, network));
    }

    private static CompletableFuture<CapabilitiesFromUser> readRetrievedCapabilityCache(FileWrapper cacheFile,
                                                                                        NetworkAccess network,
                                                                                        Crypto crypto) {
        return cacheFile.getInputStream(network, crypto, x -> { })
                .thenCompose(reader -> {
                    byte[] storeData = new byte[(int) cacheFile.getSize()];
                    return reader.readIntoArray(storeData, 0, storeData.length)
                            .thenApply(x -> CapabilitiesFromUser.fromCbor(CborObject.fromByteArray(storeData)));
                });
    }
}
