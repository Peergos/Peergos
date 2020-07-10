package peergos.shared.crypto;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** This class pads the secret up to a multiple of the given block size before encrypting and splits the ciphertext into
 * fragments which are referenced by merkle links in the serialization.
 *
 */
public class FragmentedPaddedCipherText implements Cborable {

    private final byte[] nonce;
    private final List<Multihash> cipherTextFragments;
    private final Optional<byte[]> inlinedCipherText;

    public FragmentedPaddedCipherText(byte[] nonce, List<Multihash> cipherTextFragments, Optional<byte[]> inlinedCipherText) {
        this.nonce = nonce;
        this.cipherTextFragments = cipherTextFragments;
        this.inlinedCipherText = inlinedCipherText;
        if (inlinedCipherText.isPresent() && ! cipherTextFragments.isEmpty())
            throw new IllegalStateException("Cannot have an inlined block and merkle linked blocks!");
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("n", new CborObject.CborByteArray(nonce));
        // The following change is because of a breaking change in ipfs to limit identity multihash size
        if (cipherTextFragments.size() == 1 && cipherTextFragments.get(0).isIdentity() || inlinedCipherText.isPresent()) {
            List<CborObject.CborByteArray> legacy = cipherTextFragments
                    .stream()
                    .map(h -> new CborObject.CborByteArray(h.toBytes()))
                    .collect(Collectors.toList());
            List<CborObject.CborByteArray> value = inlinedCipherText
                    .map(arr -> Collections.singletonList(new CborObject.CborByteArray(arr)))
                    .orElse(legacy);
            state.put("f", new CborObject.CborList(value));
        } else {
            state.put("f", new CborObject.CborList(cipherTextFragments
                    .stream()
                    .map(CborObject.CborMerkleLink::new)
                    .collect(Collectors.toList())));
        }
        return CborObject.CborMap.build(state);
    }

    public static FragmentedPaddedCipherText fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for FragmentedPaddedCipherText: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;

        byte[] nonce =  m.getByteArray("n");
        List<Multihash> fragmentHashes = m.getList("f").value
                .stream()
                .filter(c -> c instanceof CborObject.CborMerkleLink)
                .map(c -> ((CborObject.CborMerkleLink)c).target)
                .collect(Collectors.toList());
        Optional<byte[]> inlinedCipherText = m.getList("f").value
                .stream()
                .filter(c -> c instanceof CborObject.CborByteArray)
                .map(c -> ((CborObject.CborByteArray)c).value)
                .findFirst();
        return new FragmentedPaddedCipherText(nonce, fragmentHashes, inlinedCipherText);
    }

    protected static byte[] pad(byte[] input, int blockSize) {
        int nBlocks = (input.length + blockSize - 1) / blockSize;
        return Arrays.copyOfRange(input, 0, nBlocks * blockSize);
    }

    public static <T extends Cborable>
    CompletableFuture<Pair<FragmentedPaddedCipherText, List<FragmentWithHash>>> build(SymmetricKey from,
                                                                                      T secret,
                                                                                      int paddingBlockSize,
                                                                                      int maxFragmentSize,
                                                                                      Hasher hasher,
                                                                                      boolean allowArrayCache) {
        if (paddingBlockSize < 1)
            throw new IllegalStateException("Invalid padding block size: " + paddingBlockSize);
        byte[] nonce = from.createNonce();
        byte[] cipherText = from.encrypt(pad(secret.serialize(), paddingBlockSize), nonce);

        if (cipherText.length <= 4096 + TweetNaCl.SECRETBOX_OVERHEAD_BYTES) {
            // inline small amounts of data (small files or directories)
            FragmentWithHash frag = new FragmentWithHash(new Fragment(cipherText), Optional.empty());
            return Futures.of(new Pair<>(new FragmentedPaddedCipherText(nonce, Collections.emptyList(), Optional.of(cipherText)), Collections.singletonList(frag)));
        }

        byte[][] split = split(cipherText, maxFragmentSize, allowArrayCache);

        return Futures.combineAllInOrder(Arrays.stream(split)
                .map(d -> hasher.hash(d, true).thenApply(h -> new FragmentWithHash(new Fragment(d), Optional.of(h))))
                .collect(Collectors.toList()))
                .thenApply(frags -> {
                    List<Multihash> hashes = frags.stream()
                            .map(f -> f.hash.get())
                            .collect(Collectors.toList());
                    return new Pair<>(new FragmentedPaddedCipherText(nonce, hashes, Optional.empty()), frags);
                });
    }

    public <T> CompletableFuture<T> getAndDecrypt(SymmetricKey from,
                                                  Function<CborObject, T> fromCbor,
                                                  NetworkAccess network,
                                                  ProgressConsumer<Long> monitor) {
        if (inlinedCipherText.isPresent())
            return Futures.of(new CipherText(nonce, inlinedCipherText.get()).decrypt(from, fromCbor));
        return network.dhtClient.downloadFragments(cipherTextFragments, monitor, 1.0)
                .thenApply(fargs -> new CipherText(nonce, recombine(fargs)).decrypt(from, fromCbor));
    }

    private static byte[][] generateCache() {
        return new byte[Chunk.MAX_SIZE/Fragment.MAX_LENGTH][Fragment.MAX_LENGTH];
    }

    private static ThreadLocal<byte[][]> arrayCache = ThreadLocal.withInitial(FragmentedPaddedCipherText::generateCache);

    public static byte[][] split(byte[] input, int maxFragmentSize, boolean allowCache) {
        //calculate padding length to align to 256 bytes
        int padding = 0;
        int mod = input.length % 256;
        if (mod != 0 || input.length == 0)
            padding = 256 - mod;
        //align to 256 bytes
        int len = input.length + padding;

        //calculate the number  of fragments
        int nFragments =  len / maxFragmentSize;
        if (len % maxFragmentSize > 0)
            nFragments++;

        byte[][] split = new  byte[nFragments][];

        byte[][] cache = arrayCache.get();
        int cacheIndex = 0;
        for(int i= 0; i< nFragments; ++i) {
            int start = maxFragmentSize * i;
            int end = Math.min(input.length, start + maxFragmentSize);
            int length = end - start;
            boolean useCache = allowCache && length == Fragment.MAX_LENGTH;
            byte[] b = useCache ? cache[cacheIndex++] : new byte[length];
            System.arraycopy(input, start, b, 0, length);
            split[i] = b;
        }
        return split;
    }

    public static byte[] recombine(List<FragmentWithHash> encoded) {
        int length = 0;

        for (int i=0; i < encoded.size(); i++)
            length += encoded.get(i).fragment.data.length;

        byte[] output = new byte[length];
        int pos =  0;
        for (int i=0; i < encoded.size(); i++) {
            byte[] b = encoded.get(i).fragment.data;
            System.arraycopy(b, 0, output, pos, b.length);
            pos += b.length;
        }
        return output;
    }
}
