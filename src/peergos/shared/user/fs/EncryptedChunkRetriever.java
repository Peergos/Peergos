package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class EncryptedChunkRetriever implements FileRetriever {

    private final byte[] chunkNonce, chunkAuth;
    private final List<Multihash> fragmentHashes;
    private final Optional<CipherText> nextChunk;
    private final Fragmenter fragmenter;

    public EncryptedChunkRetriever(byte[] chunkNonce,
                                   byte[] chunkAuth,
                                   List<Multihash> fragmentHashes,
                                   Optional<CipherText> nextChunk,
                                   Fragmenter fragmenter) {
        this.chunkNonce = chunkNonce;
        this.chunkAuth = chunkAuth;
        this.fragmentHashes = fragmentHashes;
        this.nextChunk = nextChunk;
        this.fragmenter = fragmenter;
    }

    @Override
    public CompletableFuture<AsyncReader> getFile(NetworkAccess network,
                                                  SafeRandom random,
                                                  SymmetricKey dataKey,
                                                  long fileSize,
                                                  Location ourLocation,
                                                  MaybeMultihash ourExistingHash,
                                                  ProgressConsumer<Long> monitor) {
        return getChunkInputStream(network, random, dataKey, 0, fileSize, ourLocation, ourExistingHash, monitor)
                .thenApply(chunk -> {
                    Location nextChunkPointer = this.getNext(dataKey).orElse(null);
                    return new LazyInputStreamCombiner(0,
                            chunk.get().chunk.data(), nextChunkPointer,
                            chunk.get().chunk.data(), nextChunkPointer,
                            network, random, dataKey, fileSize, monitor);
                });
    }

    public CompletableFuture<Optional<LocatedEncryptedChunk>> getEncryptedChunk(long bytesRemainingUntilStart,
                                                                                long truncateTo,
                                                                                byte[] nonce,
                                                                                SymmetricKey dataKey,
                                                                                Location ourLocation,
                                                                                MaybeMultihash ourExistingHash,
                                                                                NetworkAccess network,
                                                                                ProgressConsumer<Long> monitor) {
        if (bytesRemainingUntilStart < Chunk.MAX_SIZE) {
            return network.downloadFragments(fragmentHashes, monitor, fragmenter.storageIncreaseFactor()).thenCompose(fragments -> {
                fragments = reorder(fragments, fragmentHashes);
                byte[][] collect = fragments.stream().map(f -> f.fragment.data).toArray(byte[][]::new);
                byte[] cipherText = fragmenter.recombine(collect, Chunk.MAX_SIZE);
                EncryptedChunk fullEncryptedChunk = new EncryptedChunk(ArrayOps.concat(chunkAuth, cipherText));
                if (truncateTo < Chunk.MAX_SIZE)
                    fullEncryptedChunk = fullEncryptedChunk.truncateTo((int) truncateTo);
                LocatedEncryptedChunk result = new LocatedEncryptedChunk(ourLocation, ourExistingHash, fullEncryptedChunk, nonce);
                return CompletableFuture.completedFuture(Optional.of(result));
            });
        }
        Optional<Location> next = getNext(dataKey);
        if (! next.isPresent())
            return CompletableFuture.completedFuture(Optional.empty());
        return network.getMetadata(next.get()).thenCompose(meta -> {
            if (!meta.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());

            FileAccess access = (FileAccess) meta.get();
            FileRetriever retriever = access.retriever();
            return retriever.getEncryptedChunk(bytesRemainingUntilStart - Chunk.MAX_SIZE,
                    truncateTo - Chunk.MAX_SIZE, retriever.getNonce(), dataKey,
                    next.get(), access.committedHash(), network, monitor);
        });
    }

    public CompletableFuture<Optional<Location>> getLocationAt(Location startLocation, long offset, SymmetricKey dataKey, NetworkAccess network) {
        if (offset < Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(startLocation));
        Optional<Location> next = getNext(dataKey);
        if (! next.isPresent())
            return CompletableFuture.completedFuture(Optional.empty());
        if (offset < 2*Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(next); // chunk at this location hasn't been written yet, only referenced by previous chunk
        return network.getMetadata(next.get())
                .thenCompose(meta -> meta.isPresent() ?
                        ((FileAccess)meta.get()).retriever().getLocationAt(next.get(), offset - Chunk.MAX_SIZE, dataKey, network) :
                        CompletableFuture.completedFuture(Optional.empty())
                );
    }

    public Optional<Location> getNext(SymmetricKey dataKey) {
        return this.nextChunk.map(c -> c.decrypt(dataKey, raw -> Location.fromByteArray(raw)));
    }

    public byte[] getNonce() {
        return chunkNonce;
    }

    public CompletableFuture<Optional<LocatedChunk>> getChunkInputStream(NetworkAccess network,
                                                                         SafeRandom random,
                                                                         SymmetricKey dataKey,
                                                                         long startIndex,
                                                                         long truncateTo,
                                                                         Location ourLocation,
                                                                         MaybeMultihash ourExistingHash,
                                                                         ProgressConsumer<Long> monitor) {
        return getEncryptedChunk(startIndex, truncateTo, chunkNonce, dataKey, ourLocation, ourExistingHash, network, monitor).thenCompose(fullEncryptedChunk -> {
            if (! fullEncryptedChunk.isPresent()) {
                return getLocationAt(ourLocation, startIndex, dataKey, network).thenApply(unwrittenChunkLocation ->
                        ! unwrittenChunkLocation.isPresent() ? Optional.empty() :
                                Optional.of(new LocatedChunk(unwrittenChunkLocation.get(), MaybeMultihash.empty(),
                                        new Chunk(new byte[Math.min(Chunk.MAX_SIZE, (int) (truncateTo - startIndex))],
                                                dataKey, unwrittenChunkLocation.get().getMapKey(),
                                                random.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES)))));
            }

            if (!fullEncryptedChunk.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());

            LocatedEncryptedChunk cipherText = fullEncryptedChunk.get();
            try {
                return cipherText.chunk.decrypt(dataKey, cipherText.nonce).thenCompose(original -> {
                    return CompletableFuture.completedFuture(Optional.of(new LocatedChunk(cipherText.location,
                            cipherText.existingHash,
                            new Chunk(original, dataKey, cipherText.location.getMapKey(), random.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES)))));
                });
            } catch (IllegalStateException e) {
                throw new IllegalStateException("Couldn't decrypt chunk at mapkey: " + new ByteArrayWrapper(cipherText.location.getMapKey()), e);
            }
        });
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                new CborObject.CborByteArray(chunkNonce),
                new CborObject.CborByteArray(chunkAuth),
                new CborObject.CborList(fragmentHashes
                        .stream()
                        .map(CborObject.CborMerkleLink::new)
                        .collect(Collectors.toList())),
                ! nextChunk.isPresent() ? new CborObject.CborNull() : nextChunk.get().toCbor(),
                fragmenter.toCbor()
        ));
    }

    public static EncryptedChunkRetriever fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for EncryptedChunkRetriever: " + cbor);

        List<? extends Cborable> value = ((CborObject.CborList) cbor).value;
        byte[] chunkNonce = ((CborObject.CborByteArray)value.get(0)).value;
        byte[] chunkAuth = ((CborObject.CborByteArray)value.get(1)).value;
        List<Multihash> fragmentHashes = ((CborObject.CborList)value.get(2)).value
                .stream()
                .map(c -> ((CborObject.CborMerkleLink)c).target)
                .collect(Collectors.toList());
        Optional<CipherText> nextChunk = value.get(3) instanceof CborObject.CborNull ? Optional.empty() : Optional.of(CipherText.fromCbor(value.get(3)));
        Fragmenter fragmenter = Fragmenter.fromCbor(value.get(4));
        return new EncryptedChunkRetriever(chunkNonce, chunkAuth, fragmentHashes, nextChunk, fragmenter);
    }

    private static List<FragmentWithHash> reorder(List<FragmentWithHash> fragments, List<Multihash> hashes) {
        FragmentWithHash[] res = new FragmentWithHash[fragments.size()];
        for (FragmentWithHash f: fragments) {
            for (int index = 0; index < res.length; index++)
                if (hashes.get(index).equals(f.hash))
                    res[index] = f;
        }
        return Arrays.asList(res);
    }

    private static List<byte[]> split(byte[] arr, int size) {
        int length = arr.length/size;
        List<byte[]> res = new ArrayList<>();
        for (int i=0; i < length; i++)
            res.add(Arrays.copyOfRange(arr, i*size, (i+1)*size));
        return res;
    }
}
