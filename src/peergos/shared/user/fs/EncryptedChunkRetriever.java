package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class EncryptedChunkRetriever implements FileRetriever {

    private final byte[] chunkNonce, chunkAuth;
    private final List<Multihash> fragmentHashes;
    private final Location nextChunk;
    private final peergos.shared.user.fs.Fragmenter fragmenter;

    public EncryptedChunkRetriever(byte[] chunkNonce, byte[] chunkAuth, List<Multihash> fragmentHashes, Location nextChunk, Fragmenter fragmenter) {
        this.chunkNonce = chunkNonce;
        this.chunkAuth = chunkAuth;
        this.fragmentHashes = fragmentHashes;
        this.nextChunk = nextChunk;
        this.fragmenter = fragmenter;
    }

    public CompletableFuture<AsyncReader> getFile(UserContext context, SymmetricKey dataKey, long fileSize,
                                                  Location ourLocation, ProgressConsumer<Long> monitor) {
        return getChunkInputStream(context, dataKey, 0, fileSize, ourLocation, monitor)
                .thenApply(chunk -> new LazyInputStreamCombiner(this, context, dataKey, chunk.get().chunk.data(), fileSize, monitor));
    }

    public CompletableFuture<Optional<LocatedEncryptedChunk>> getEncryptedChunk(long bytesRemainingUntilStart, long truncateTo,
                                                                                byte[] nonce, SymmetricKey dataKey,
                                                                                Location ourLocation, UserContext context, ProgressConsumer<Long> monitor) {
        if (bytesRemainingUntilStart < Chunk.MAX_SIZE) {
            return context.downloadFragments(fragmentHashes, monitor).thenCompose(fragments -> {
                fragments = reorder(fragments, fragmentHashes);
                byte[][] collect = fragments.stream().map(f -> f.fragment.data).toArray(byte[][]::new);
                byte[] cipherText = fragmenter.recombine(collect, Chunk.MAX_SIZE);
                EncryptedChunk fullEncryptedChunk = new EncryptedChunk(ArrayOps.concat(chunkAuth, cipherText));
                if (truncateTo < Chunk.MAX_SIZE)
                    fullEncryptedChunk = fullEncryptedChunk.truncateTo((int) truncateTo);
                return CompletableFuture.completedFuture(Optional.of(new LocatedEncryptedChunk(ourLocation, fullEncryptedChunk, nonce)));
            });
        }
        return context.getMetadata(getNext()).thenCompose(meta ->
             !meta.isPresent() ? CompletableFuture.completedFuture(Optional.empty()) :
                     meta.get().retriever().getEncryptedChunk(bytesRemainingUntilStart - Chunk.MAX_SIZE,
                             truncateTo - Chunk.MAX_SIZE, meta.get().retriever().getNonce(), dataKey, getNext(), context, monitor)
            );
    }

    public CompletableFuture<Optional<Location>> getLocationAt(Location startLocation, long offset, UserContext context) {
        if (offset < Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(startLocation));
        Location next = getNext();
        if (next == null)
            return CompletableFuture.completedFuture(Optional.empty());
        if (offset < 2*Chunk.MAX_SIZE)
            return CompletableFuture.completedFuture(Optional.of(next)); // chunk at this location hasn't been written yet, only referenced by previous chunk
        return context.getMetadata(next)
                .thenCompose(meta -> meta.isPresent() ?
                        meta.get().retriever().getLocationAt(next, offset - Chunk.MAX_SIZE, context) :
                        CompletableFuture.completedFuture(Optional.empty())
                );
    }

    public Location getNext() {
        return this.nextChunk;
    }

    public byte[] getNonce() {
        return chunkNonce;
    }

    public CompletableFuture<Optional<LocatedChunk>> getChunkInputStream(UserContext context, SymmetricKey dataKey,
                                                                         long startIndex, long truncateTo,
                                                                         Location ourLocation, ProgressConsumer<Long> monitor) {
        return getEncryptedChunk(startIndex, truncateTo, chunkNonce, dataKey, ourLocation, context, monitor).thenCompose(fullEncryptedChunk -> {

            if (!fullEncryptedChunk.isPresent()) {
                return getLocationAt(ourLocation, startIndex, context).thenApply(unwrittenChunkLocation ->
                        !unwrittenChunkLocation.isPresent() ? Optional.empty() :
                                Optional.of(new LocatedChunk(unwrittenChunkLocation.get(),
                                        new Chunk(new byte[Math.min(Chunk.MAX_SIZE, (int) (truncateTo - startIndex))],
                                                dataKey, unwrittenChunkLocation.get().getMapKey(),
                                                context.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES)))));
            }

            if (!fullEncryptedChunk.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());

            try {
                byte[] original = fullEncryptedChunk.get().chunk.decrypt(dataKey, fullEncryptedChunk.get().nonce);
                return CompletableFuture.completedFuture(Optional.of(new LocatedChunk(fullEncryptedChunk.get().location,
                        new Chunk(original, dataKey, fullEncryptedChunk.get().location.getMapKey(), context.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES)))));
            } catch (IllegalStateException e) {
                throw new IllegalStateException("Couldn't decrypt chunk at mapkey: " + new ByteArrayWrapper(fullEncryptedChunk.get().location.getMapKey()), e);
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
                nextChunk == null ? new CborObject.CborNull() : nextChunk.toCbor(),
                fragmenter.toCbor()
        ));
    }

    public static EncryptedChunkRetriever fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for EncryptedChunkRetriever: " + cbor);

        List<CborObject> value = ((CborObject.CborList) cbor).value;
        byte[] chunkNonce = ((CborObject.CborByteArray)value.get(0)).value;
        byte[] chunkAuth = ((CborObject.CborByteArray)value.get(1)).value;
        List<Multihash> fragmentHashes = ((CborObject.CborList)value.get(2)).value
                .stream()
                .map(c -> ((CborObject.CborMerkleLink)c).target)
                .collect(Collectors.toList());
        Location nextChunk = value.get(3) instanceof CborObject.CborNull ? null : Location.fromCbor(value.get(3));
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
