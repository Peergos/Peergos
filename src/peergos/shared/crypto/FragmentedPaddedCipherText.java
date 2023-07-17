package peergos.shared.crypto;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.auth.*;
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
    private final Optional<byte[]> header; // Present on all but legacy or inlined chunks, contains secretbox auth and cbor padding
    private final List<Cid> cipherTextFragments;
    private final List<BatWithId> bats;
    private final Optional<byte[]> inlinedCipherText;

    public FragmentedPaddedCipherText(byte[] nonce,
                                      Optional<byte[]> header,
                                      List<Cid> cipherTextFragments,
                                      List<BatWithId> bats,
                                      Optional<byte[]> inlinedCipherText) {
        this.nonce = nonce;
        this.header = header;
        this.cipherTextFragments = cipherTextFragments;
        this.bats = bats;
        this.inlinedCipherText = inlinedCipherText;
        if (inlinedCipherText.isPresent() && ! cipherTextFragments.isEmpty())
            throw new IllegalStateException("Cannot have an inlined block and merkle linked blocks!");
    }

    public boolean isInline() {
        return inlinedCipherText.isPresent();
    }

    public List<Cid> getFragments() {
        return cipherTextFragments;
    }

    public List<BatWithId> getBats() {
        return bats;
    }

    public FragmentedPaddedCipherText withFragments(List<Cid> fragments) {
        return new FragmentedPaddedCipherText(nonce, header, fragments, bats, inlinedCipherText);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("n", new CborObject.CborByteArray(nonce));
        header.ifPresent(h -> state.put("h", new CborObject.CborByteArray(h)));
        // The following change is because of a breaking change in ipfs to limit identity multihash size
        if (cipherTextFragments.size() == 1 && cipherTextFragments.get(0).isIdentity() || inlinedCipherText.isPresent()) {
            List<CborObject.CborByteArray> legacy = cipherTextFragments
                    .stream()
                    .map(h -> new CborObject.CborByteArray(h.getHash()))
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
            state.put("bats", new CborObject.CborList(bats));
        }
        return CborObject.CborMap.build(state);
    }

    public static FragmentedPaddedCipherText fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for FragmentedPaddedCipherText: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;

        byte[] nonce =  m.getByteArray("n");
        Optional<byte[]> header = m.getOptionalByteArray("h");
        List<Cid> fragmentHashes = m.getList("f").value
                .stream()
                .filter(c -> c instanceof CborObject.CborMerkleLink)
                .map(c -> (Cid) ((CborObject.CborMerkleLink)c).target)
                .collect(Collectors.toList());
        Optional<byte[]> inlinedCipherText = m.getList("f").value
                .stream()
                .filter(c -> c instanceof CborObject.CborByteArray)
                .map(c -> ((CborObject.CborByteArray)c).value)
                .findFirst();
        List<BatWithId> bats = m.containsKey("bats") ? m.getList("bats", BatWithId::fromCbor) : Collections.emptyList();
        return new FragmentedPaddedCipherText(nonce, header, fragmentHashes, bats, inlinedCipherText);
    }

    protected static byte[] pad(byte[] input, int excluded, int blockSize) {
        int nBlocks = (input.length - excluded + blockSize - 1) / blockSize;
        return Arrays.copyOfRange(input, 0, nBlocks * blockSize + excluded);
    }

    public static <T extends Cborable>
    CompletableFuture<Pair<FragmentedPaddedCipherText, List<FragmentWithHash>>> build(SymmetricKey from,
                                                                                      T secret,
                                                                                      int paddingBlockSize,
                                                                                      int maxFragmentSize,
                                                                                      Optional<BatId> mirrorBat,
                                                                                      SafeRandom random,
                                                                                      Hasher hasher,
                                                                                      boolean allowArrayCache) {
        if (paddingBlockSize < 1)
            throw new IllegalStateException("Invalid padding block size: " + paddingBlockSize);
        byte[] nonce = from.createNonce();
        byte[] plainText = secret.serialize();
        // input chunk size: 0,    5,    4090, 4096, 4097
        // padded to:        4096, 4096, 4096, 4096, 8192
        int maxCborOverhead = 6;
        int serializationOverhead = plainText.length <= paddingBlockSize ? 0 : maxCborOverhead;
        byte[] padded = pad(plainText, serializationOverhead, paddingBlockSize);
        byte[] cipherText = from.encrypt(padded, nonce);

        if (padded.length <= 4096 + maxCborOverhead) {
            // inline small amounts of data (small files or directories)
            FragmentedPaddedCipherText chunk = new FragmentedPaddedCipherText(nonce, Optional.empty(),
                    Collections.emptyList(), Collections.emptyList(), Optional.of(cipherText));
            return Futures.of(new Pair<>(chunk, Collections.emptyList()));
        }

        int headerSize = cipherText.length % paddingBlockSize;
        Optional<byte[]> header = Optional.of(Arrays.copyOfRange(cipherText, 0, headerSize));
        byte[][] split = split(cipherText, headerSize, maxFragmentSize, allowArrayCache);
        int nBlocks = split.length;
        List<Bat> blockBats = IntStream.range(0, nBlocks)
                .mapToObj(i -> Bat.random(random))
                .collect(Collectors.toList());

        return Futures.combineAllInOrder(IntStream.range(0, nBlocks)
                .mapToObj(i -> ArrayOps.concat(Bat.createRawBlockPrefix(blockBats.get(i), mirrorBat), split[i]))
                .map(d -> hasher.hash(d, true).thenApply(h -> new FragmentWithHash(new Fragment(d), Optional.of(h))))
                .collect(Collectors.toList()))
                .thenCompose(frags -> {
                    List<Cid> hashes = frags.stream()
                            .map(f -> f.hash.get())
                            .collect(Collectors.toList());
                    return Futures.combineAllInOrder(blockBats.stream()
                            .map(b -> b.calculateId(hasher).thenApply(id -> new BatWithId(b, id.id)))
                            .collect(Collectors.toList()))
                            .thenApply(batsAndIds -> new Pair<>(new FragmentedPaddedCipherText(nonce, header, hashes, batsAndIds, Optional.empty()), frags));
                });
    }

    public <T> CompletableFuture<T> getAndDecrypt(PublicKeyHash owner,
                                                  SymmetricKey from,
                                                  Function<CborObject, T> fromCbor,
                                                  Hasher h,
                                                  NetworkAccess network,
                                                  ProgressConsumer<Long> monitor) {
        if (inlinedCipherText.isPresent()) {
            if (header.isPresent())
                return Futures.of(new CipherText(nonce, ArrayOps.concat(header.get(), inlinedCipherText.get())).decrypt(from, fromCbor, monitor));
            return Futures.of(new CipherText(nonce, inlinedCipherText.get()).decrypt(from, fromCbor, monitor));
        }
        return network.dhtClient.downloadFragments(owner, cipherTextFragments, bats, h, monitor, 1.0)
                .thenApply(frags -> frags.stream()
                        .map(f -> new FragmentWithHash(new Fragment(Bat.removeRawBlockBatPrefix(f.fragment.data)), f.hash))
                        .collect(Collectors.toList()))
                .thenApply(fargs -> new CipherText(nonce, recombine(header, fargs)).decrypt(from, fromCbor));
    }

    private static byte[][] generateCache() {
        return new byte[Chunk.MAX_SIZE/Fragment.MAX_LENGTH][Fragment.MAX_LENGTH];
    }

    private static ThreadLocal<byte[][]> arrayCache = ThreadLocal.withInitial(FragmentedPaddedCipherText::generateCache);

    private static byte[][] split(byte[] input, int inputStartIndex, int maxFragmentSize, boolean allowCache) {
        //calculate padding length to align to 256 bytes
        int padding = 0;
        int mod = (input.length - inputStartIndex) % 256;
        if (mod != 0 || (input.length - inputStartIndex) == 0)
            padding = 256 - mod;
        //align to 256 bytes
        int len = input.length - inputStartIndex + padding;

        //calculate the number  of fragments
        int nFragments =  len / maxFragmentSize;
        if (len % maxFragmentSize > 0)
            nFragments++;

        byte[][] split = new  byte[nFragments][];

        byte[][] cache = arrayCache.get();
        int cacheIndex = 0;
        for (int i= 0; i< nFragments; ++i) {
            int start = inputStartIndex + maxFragmentSize * i;
            int end = Math.min(input.length, start + maxFragmentSize);
            int length = end - start;
            boolean useCache = allowCache && length == Fragment.MAX_LENGTH;
            byte[] b = useCache ? cache[cacheIndex++] : new byte[length];
            System.arraycopy(input, start, b, 0, length);
            split[i] = b;
        }
        return split;
    }

    private static byte[] recombine(Optional<byte[]> header, List<FragmentWithHash> encoded) {
        int length = 0;

        for (int i=0; i < encoded.size(); i++)
            length += encoded.get(i).fragment.data.length;

        int headerSize = header.map(h -> h.length).orElse(0);
        byte[] output = new byte[headerSize + length];
        header.ifPresent(h -> System.arraycopy(h, 0, output, 0, headerSize));
        int pos = headerSize;
        for (int i=0; i < encoded.size(); i++) {
            byte[] b = encoded.get(i).fragment.data;
            System.arraycopy(b, 0, output, pos, b.length);
            pos += b.length;
        }
        return output;
    }
}
