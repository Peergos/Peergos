package peergos.shared.user.fs;

import peergos.shared.NetworkAccess;
import peergos.shared.crypto.random.SafeRandom;
import peergos.shared.user.EntryPoint;
import peergos.shared.user.TrieNode;
import peergos.shared.user.WriterData;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class FastSharing {
    public static final int FILE_POINTER_SIZE = 159; // fp.toCbor().toByteArray() DOESN'T INCLUDE .secret
    public static final int SHARING_FILE_MAX_SIZE = FILE_POINTER_SIZE * 10000; // record size * x
    public static final String SHARING_FILE_PREFIX = "sharing.";
    public static final String SEPARATOR = "#";
    public static final String RETRIEVED_CAPABILITY_CACHE = SEPARATOR + ".cache";

    public static CompletableFuture<Boolean> getSharingLinks(FileTreeNode root, Pair<FileTreeNode, String> friend,
                                                              List<RetrievedCapability> retrievedCapabilityCache,
                                                              NetworkAccess network, SafeRandom random, Fragmenter fragmenter) {
        return getSharingLinks(friend.left, retrievedCapabilityCache, network, random).thenCompose(res ->
            FastSharing.saveRetrievedCapabilityCache(root, friend.right, network, random, fragmenter, retrievedCapabilityCache)
        );
    }

    public static CompletableFuture<Boolean> getSharingLinks(FileTreeNode root, List<RetrievedCapability> retrievedCapabilityCache, NetworkAccess network, SafeRandom random) {
        return root.getChildren(network)
                .thenCompose(children -> {
                    //GWT complains about following line
                    //List<FileTreeNode> sharingFiles = children.stream().sorted(Comparator.comparing(f -> f.getFileProperties().modified)).collect(Collectors.toList());
                    List<FileTreeNode> sharingFiles = new ArrayList<>(children);
                    Collections.sort(sharingFiles, new Comparator<FileTreeNode>() {
                        @Override
                        public int compare(FileTreeNode o1, FileTreeNode o2) {
                            return o1.getFileProperties().modified.compareTo(o2.getFileProperties().modified);
                        }
                    });
                    return Futures.reduceAll(sharingFiles,
                            true,
                            (x, sharingFile) -> FastSharing.readSharingFile(root.getName(), sharingFile, retrievedCapabilityCache, network, random),
                            (a, b) -> a && b);
                });
    }

    private static List<EntryPoint> retrievedCapabilityToEntryPoint(HashMap<String, List<RetrievedCapability>> combinedRetrievedCapabilityCache) {
        return combinedRetrievedCapabilityCache.entrySet().stream().flatMap(e -> {
            List<EntryPoint> result = e.getValue().stream().map( rc -> new EntryPoint(rc.fp, e.getKey(), Collections.emptySet(), Collections.emptySet())).collect(Collectors.toList());
            return result.stream();
        }).collect(Collectors.toList());
    }

    public static CompletableFuture<Boolean> readSharingFile(String ownerName, FileTreeNode file, List<RetrievedCapability> retrievedCapabilityCache
            , NetworkAccess network, SafeRandom random) {
        return file.getInputStream(network, random, x -> {}).thenCompose(reader -> {
            int currentFileSize = (int) file.getSize();
            List<Integer> offsets = IntStream.range(0, currentFileSize / FastSharing.FILE_POINTER_SIZE)
                    .mapToObj(e -> e * FastSharing.FILE_POINTER_SIZE).collect(Collectors.toList());
            return Futures.reduceAll(offsets,
                    true,
                    (x, offset) -> readSharingRecord(ownerName, reader, offset, retrievedCapabilityCache, network),
                    (a, b) -> a && b);
        });
    }

    private static CompletableFuture<Boolean> readSharingRecord(String ownerName, AsyncReader reader, int offset, List<RetrievedCapability> retrievedCapabilityCache
            , NetworkAccess network) {
        byte[] serialisedFilePointer = new byte[FastSharing.FILE_POINTER_SIZE];
        return reader.seek( 0, offset).thenCompose( currentPos ->
                currentPos.readIntoArray(serialisedFilePointer, 0, FastSharing.FILE_POINTER_SIZE)
                        .thenCompose(bytesRead -> {
                            FilePointer pointer = FilePointer.fromByteArray(serialisedFilePointer);
                            EntryPoint entry = new EntryPoint(pointer, ownerName, Collections.emptySet(), Collections.emptySet());
                            return network.retrieveEntryPoint(entry).thenCompose( optFTN -> {
                                if(optFTN.isPresent()) {
                                    FileTreeNode ftn = optFTN.get();
                                    try {
                                        return ftn.getPath(network).thenCompose(path -> {
                                            retrievedCapabilityCache.add(new RetrievedCapability(path, pointer));
                                            return CompletableFuture.completedFuture(true);
                                        });
                                    } catch (NoSuchElementException nsee) {
                                        return CompletableFuture.completedFuture(true); //file no longer exists
                                    }
                                } else {
                                    return CompletableFuture.completedFuture(true);
                                }
                            });
                        })
        );
    }

    public static CompletableFuture<Boolean> saveRetrievedCapabilityCache(FileTreeNode home, String friend,
                                                                  NetworkAccess network, SafeRandom random, Fragmenter fragmenter,
                                                                  List<RetrievedCapability> retrievedCapabilities) {
        RetrievedCapabilityCache retrievedCapabilityCache = new RetrievedCapabilityCache(retrievedCapabilities);
        byte[] data = retrievedCapabilityCache.serialize();
        AsyncReader.ArrayBacked dataReader = new AsyncReader.ArrayBacked(data);
        return home.uploadFile(friend + FastSharing.RETRIEVED_CAPABILITY_CACHE, dataReader, true, (long) data.length,
                true, network, random, x-> {}, fragmenter).thenApply(x -> true);
    }

    public static CompletableFuture<Map<String, List<RetrievedCapability>>> readRetrievedCapabilityCache(Set<FileTreeNode> children,
                                                                             NetworkAccess network, SafeRandom random) {
        Map<String, List<RetrievedCapability>> combinedRetrievedCapabilityCache = new HashMap<>();

        List<FileTreeNode> capabilityCacheFiles = children.stream()
                .filter(f -> f.getName().endsWith(FastSharing.RETRIEVED_CAPABILITY_CACHE))
                .collect(Collectors.toList());
        List<Pair<String, FileTreeNode>> friendAndCapabilityCacheFileList = capabilityCacheFiles.stream()
                .map(f -> new Pair<>(f.getName().substring(0, f.getName().indexOf(FastSharing.SEPARATOR)), f))
                .collect(Collectors.toList());
        return Futures.reduceAll(friendAndCapabilityCacheFileList,
                true,
                (x, cacheFile) -> {
                    return readRetrievedCapabilityCache(cacheFile.right, network, random).thenApply(cache -> {
                        combinedRetrievedCapabilityCache.put(cacheFile.left, cache.getRetrievedCapabilities());
                        return true;
                    });
                },
                (a, b) -> a && b)
                .thenApply(result -> combinedRetrievedCapabilityCache);
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
