package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** An instance of EncryptedChunkRetriever holds a list of fragment hashes for a chunk, and the nonce and auth used in
 *  decrypting the resulting chunk, along with an encrypted link to the next chunk (if any).
 *
 */
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
        AbsoluteCapability ourCap = AbsoluteCapability.build(ourLocation, dataKey);
        return getChunkInputStream(network, random, 0, fileSize, ourCap, ourExistingHash, monitor)
                .thenApply(chunk -> {
                    Location nextChunkPointer = this.getNextMapLabel(dataKey).map(ourLocation::withMapKey).orElse(null);
                    return new LazyInputStreamCombiner(0,
                            chunk.get().chunk.data(), nextChunkPointer,
                            chunk.get().chunk.data(), nextChunkPointer,
                            network, random, dataKey, fileSize, monitor);
                });
    }

    public CompletableFuture<Optional<LocatedEncryptedChunk>> getEncryptedChunk(long bytesRemainingUntilStart,
                                                                                long truncateTo,
                                                                                byte[] nonce,
                                                                                AbsoluteCapability ourCap,
                                                                                MaybeMultihash ourExistingHash,
                                                                                NetworkAccess network,
                                                                                ProgressConsumer<Long> monitor) {
        if (bytesRemainingUntilStart < Chunk.MAX_SIZE) {
            return network.downloadFragments(fragmentHashes, monitor, fragmenter.storageIncreaseFactor()).thenCompose(fragments -> {
                fragments = reorder(fragments, fragmentHashes);
                byte[][] collect = fragments.stream().map(f -> f.fragment.data).toArray(byte[][]::new);
                byte[] authAndCipherText = fragmenter.recombine(collect, chunkAuth.length, Chunk.MAX_SIZE);
                System.arraycopy(chunkAuth, 0, authAndCipherText, 0, chunkAuth.length);
                EncryptedChunk fullEncryptedChunk = new EncryptedChunk(authAndCipherText);
                if (truncateTo < Chunk.MAX_SIZE)
                    fullEncryptedChunk = fullEncryptedChunk.truncateTo((int) truncateTo);
                LocatedEncryptedChunk result = new LocatedEncryptedChunk(ourCap.getLocation(), ourExistingHash, fullEncryptedChunk, nonce);
                return CompletableFuture.completedFuture(Optional.of(result));
            });
        }
        Optional<byte[]> next = getNextMapLabel(ourCap.rBaseKey);
        if (! next.isPresent())
            return CompletableFuture.completedFuture(Optional.empty());
        AbsoluteCapability nextCap = ourCap.withMapKey(next.get());
        return network.getMetadata(nextCap).thenCompose(meta -> {
            if (!meta.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());

            FileAccess access = (FileAccess) meta.get();
            FileRetriever retriever = access.retriever();
            return retriever.getEncryptedChunk(bytesRemainingUntilStart - Chunk.MAX_SIZE,
                    truncateTo - Chunk.MAX_SIZE, retriever.getNonce(), nextCap,
                    access.committedHash(), network, monitor);
        });
    }

    public CompletableFuture<Optional<byte[]>> getMapLabelAt(AbsoluteCapability startCap, long offset, NetworkAccess network) {
        if (offset < Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(startCap.getMapKey()));
        Optional<byte[]> next = getNextMapLabel(startCap.rBaseKey);
        if (! next.isPresent())
            return CompletableFuture.completedFuture(Optional.empty());
        if (offset < 2*Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(next); // chunk at this location hasn't been written yet, only referenced by previous chunk
        return network.getMetadata(startCap.withMapKey(next.get()))
                .thenCompose(meta -> meta.isPresent() ?
                        ((FileAccess)meta.get()).retriever().getMapLabelAt(startCap.withMapKey(next.get()), offset - Chunk.MAX_SIZE, network) :
                        CompletableFuture.completedFuture(Optional.empty())
                );
    }

    public Optional<byte[]> getNextMapLabel(SymmetricKey dataKey) {
        return this.nextChunk.map(c -> c.decrypt(dataKey, cbor -> ((CborObject.CborByteArray)cbor).value));
    }

    public byte[] getNonce() {
        return chunkNonce;
    }

    public CompletableFuture<Optional<LocatedChunk>> getChunkInputStream(NetworkAccess network,
                                                                         SafeRandom random,
                                                                         long startIndex,
                                                                         long truncateTo,
                                                                         AbsoluteCapability ourCap,
                                                                         MaybeMultihash ourExistingHash,
                                                                         ProgressConsumer<Long> monitor) {
        return getEncryptedChunk(startIndex, truncateTo, chunkNonce, ourCap, ourExistingHash, network, monitor).thenCompose(fullEncryptedChunk -> {
            if (! fullEncryptedChunk.isPresent()) {
                return getMapLabelAt(ourCap, startIndex, network).thenApply(unwrittenChunkLocation ->
                        ! unwrittenChunkLocation.isPresent() ? Optional.empty() :
                                Optional.of(new LocatedChunk(ourCap.getLocation().withMapKey(unwrittenChunkLocation.get()), MaybeMultihash.empty(),
                                        new Chunk(new byte[Math.min(Chunk.MAX_SIZE, (int) (truncateTo - startIndex))],
                                                ourCap.rBaseKey, unwrittenChunkLocation.get(),
                                                ourCap.rBaseKey.createNonce()))));
            }

            if (!fullEncryptedChunk.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());

            LocatedEncryptedChunk cipherText = fullEncryptedChunk.get();
            try {
                return cipherText.chunk.decrypt(ourCap.rBaseKey, cipherText.nonce).thenCompose(original -> {
                    return CompletableFuture.completedFuture(Optional.of(new LocatedChunk(cipherText.location,
                            cipherText.existingHash,
                            new Chunk(original, ourCap.rBaseKey, cipherText.location.getMapKey(), ourCap.rBaseKey.createNonce()))));
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
