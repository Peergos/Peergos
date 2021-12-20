package peergos.shared.corenode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class OpLog implements Cborable, Account, MutablePointers, ContentAddressedStorage, BatCave {
    private static final int ED25519_SIGNATURE_SIZE = 64;

    public final List<Either<PointerWrite, BlockWrite>> operations;
    private final Map<Multihash, byte[]> storage = new HashMap<>();
    public Pair<LoginData, byte[]> loginData;
    public Optional<Pair<BatWithId, byte[]>> mirrorBat;

    public OpLog(List<Either<PointerWrite, BlockWrite>> operations,
                 Pair<LoginData, byte[]> loginData,
                 Optional<Pair<BatWithId, byte[]>> mirrorBat) {
        this.operations = operations;
        this.loginData = loginData;
        this.mirrorBat = mirrorBat;
    }

    @Override
    public synchronized CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        operations.add(Either.a(new PointerWrite(writer, writerSignedBtreeRootHash)));
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        for (int i= operations.size() - 1; i>=0; i--) {
            Either<PointerWrite, BlockWrite> op = operations.get(i);
            if (op.isA() && op.a().writer.equals(writer))
                return Futures.of(Optional.of(op.a().writerSignedChampRootCas));
        }
        throw new IllegalStateException("Unknown writer: " + writer);
    }

    @Override
    public synchronized CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth) {
        loginData = new Pair<>(login, auth);
        return Futures.of(true);
    }

    @Override
    public synchronized CompletableFuture<UserStaticData> getLoginData(String username,
                                                                       PublicSigningKey authorisedReader,
                                                                       byte[] auth) {
        if (loginData == null)
            throw new IllegalStateException("No login data present!");
        if (! loginData.left.username.equals(username))
            throw new IllegalStateException("No login data present for " + username);
        if (! loginData.left.authorisedReader.equals(authorisedReader))
            throw new IllegalStateException("You are not authorised to login as " + username);
        return Futures.of(loginData.left.entryPoints);
    }

    @Override
    public Optional<Bat> getBat(BatId id) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth) {
        if (mirrorBat.isPresent())
            throw new IllegalStateException("Only 1 mirror BAT allowed in OpLog!");
        mirrorBat = Optional.of(new Pair<>(new BatWithId(bat, id.id), auth));
        return Futures.of(true);
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Cid> id() {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return Futures.of(new TransactionId("1"));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signedHashes,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return Futures.combineAllInOrder(IntStream.range(0, blocks.size())
                .mapToObj(i -> put(writer, signedHashes.get(i), blocks.get(i), false))
                .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash, String auth) {
        return Futures.of(Optional.ofNullable(storage.get(hash)).map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signedHashes,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressCounter) {
        return Futures.combineAllInOrder(IntStream.range(0, blocks.size())
                .mapToObj(i -> put(writer, signedHashes.get(i), blocks.get(i), true))
                .collect(Collectors.toList()));
    }

    private CompletableFuture<Multihash> put(PublicKeyHash writer, byte[] signedHash, byte[] block, boolean isRaw) {
        // Assume we are using ed25519 for now
        byte[] hash = Arrays.copyOfRange(signedHash, ED25519_SIGNATURE_SIZE, signedHash.length);
        Multihash h = new Cid(1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
        storage.put(h, block);
        operations.add(Either.b(new BlockWrite(writer, signedHash, block, isRaw)));
        return Futures.of(h);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash, String auth) {
        return Futures.of(Optional.ofNullable(storage.get(hash)));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
        return Futures.of(new ArrayList<>(storage.values()));
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Boolean> gc() {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        throw new IllegalStateException("Unsupported operation!");
    }

    public static final class BlockWrite implements Cborable {
        public final PublicKeyHash writer;
        public final byte[] signature, block;
        public final boolean isRaw;

        public BlockWrite(PublicKeyHash writer, byte[] signature, byte[] block, boolean isRaw) {
            this.writer = writer;
            this.signature = signature;
            this.block = block;
            this.isRaw = isRaw;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("w", writer);
            state.put("s", new CborObject.CborByteArray(signature));
            state.put("b", new CborObject.CborByteArray(block));
            state.put("r", new CborObject.CborBoolean(isRaw));
            return CborObject.CborMap.build(state);
        }

        public static BlockWrite fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor!");
            CborObject.CborMap m = (CborObject.CborMap) cbor;

            PublicKeyHash writer = m.get("w", PublicKeyHash::fromCbor);
            byte[] signature = m.getByteArray("s");
            byte[] block = m.getByteArray("b");
            boolean isRaw = m.getBoolean("r");
            return new BlockWrite(writer, signature, block, isRaw);
        }
    }

    public static final class PointerWrite implements Cborable {
        public final PublicKeyHash writer;
        public final byte[] writerSignedChampRootCas;

        public PointerWrite(PublicKeyHash writer, byte[] writerSignedChampRootCas) {
            this.writer = writer;
            this.writerSignedChampRootCas = writerSignedChampRootCas;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("w", writer);
            state.put("s", new CborObject.CborByteArray(writerSignedChampRootCas));
            return CborObject.CborMap.build(state);
        }

        public static PointerWrite fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor!");
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            PublicKeyHash writer = m.get("w", PublicKeyHash::fromCbor);
            byte[] signedUpdate = m.getByteArray("s");
            return new PointerWrite(writer, signedUpdate);
        }
    }

    private static Either<PointerWrite, BlockWrite> parseOperation(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for OpLog!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        if (m.containsKey("r"))
            return Either.b(BlockWrite.fromCbor(cbor));
        return Either.a(PointerWrite.fromCbor(cbor));
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("ops", new CborObject.CborList(operations.stream()
                .map(e -> e.map(PointerWrite::toCbor, BlockWrite::toCbor))
                .collect(Collectors.toList())));
        if (loginData != null) {
            state.put("login", loginData.left);
            state.put("loginAuth", new CborObject.CborByteArray(loginData.right));
        }
        mirrorBat.ifPresent(t -> {
            state.put("b", t.left);
            state.put("a", new CborObject.CborByteArray(t.right));
        });
        return CborObject.CborMap.build(state);
    }

    public static OpLog fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for OpLog!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        List<Either<PointerWrite, BlockWrite>> ops = m.getList("ops", OpLog::parseOperation);
        Optional<Pair<BatWithId, byte[]>> mirrorBat;
        if (m.containsKey("b")) {
            mirrorBat = Optional.of(new Pair<>(m.get("b", BatWithId::fromCbor), m.getByteArray("a")));
        } else
            mirrorBat = Optional.empty();

        if (m.containsKey("login")) {
            LoginData login = m.get("login", LoginData::fromCbor);
            byte[] loginAuth = m.getByteArray("loginAuth");
            return new OpLog(ops, new Pair<>(login, loginAuth), mirrorBat);
        }
        return new OpLog(ops, null, mirrorBat);
    }
}
