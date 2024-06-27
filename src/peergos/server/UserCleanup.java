package peergos.server;

import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;
import java.util.stream.*;

public class UserCleanup {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true).get();
        String username = args[0];
        String password = args[1];
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        long usage = context.getSpaceUsage().join();
//        checkRawUsage(context);
        clearUnreachableChampNodes(context);
    }

    public static void clearUnreachableChampNodes(UserContext c) {
        //  First clear any failed uploads
//        c.cleanPartialUploads(t -> true).join();

        ContentAddressedStorage storage = c.network.dhtClient;
        MutablePointers mutable = c.network.mutable;
        Hasher hasher = c.crypto.hasher;
        PublicKeyHash owner = c.signer.publicKeyHash;
        FileWrapper root = c.getUserRoot().join();
        List<Multihash> hosts = c.network.coreNode.getStorageProviders(owner);
        Set<PublicKeyHash> writers = DeletableContentAddressedStorage.getOwnedKeysRecursive(owner, owner, mutable,
                        (h, s) -> ContentAddressedStorage.getWriterData(owner, h, s, storage), storage, hasher).join()
                .stream()
                .filter(w ->  !w.equals(owner))
                .collect(Collectors.toSet());

        Set<PublicKeyHash> namedOwnedKeys = WriterData.getWriterData(owner, owner, mutable, storage).join()
                .props.get().namedOwnedKeys.values().stream()
                .map(p -> p.ownedKey)
                .collect(Collectors.toSet());

        Map<PublicKeyHash, PublicKeyHash> toParent = new HashMap<>();
        for (PublicKeyHash writer : writers) {
            Set<PublicKeyHash> owned = DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writer, mutable,
                    (h, s) -> ContentAddressedStorage.getWriterData(owner, h, s, storage), storage, hasher).join();
            for (PublicKeyHash child : owned) {
                toParent.put(child, writer);
            }
        }

        Map<SigningPrivateKeyAndPublicHash, Map<String, ByteArrayWrapper>> reachableKeys = new HashMap<>();
        BatWithId mirrorBat = c.getMirrorBat().join().get();
        traverseDescendants(root, "/" + c.username, (s, cap, p, fopt) -> {
            reachableKeys.putIfAbsent(s, new HashMap<>());
            reachableKeys.get(s).put(p, new ByteArrayWrapper(cap.getMapKey()));
            fopt.ifPresent(f -> {
                RetrievedCapability rcap = f.getPointer();
                int nBats = f.getPointer().fileAccess.bats.size();
                boolean addToFragmentsOnly = rcap.capability.bat.isEmpty() || nBats == 2;
                System.out.println(p);
                f.addMirrorBat(mirrorBat.id(), addToFragmentsOnly, c.network).join();
            });
            return true;
        }, c);

        // handle each writing space separately
        for (PublicKeyHash writer : writers) {
            Map<ByteArrayWrapper, CborObject.CborMerkleLink> allKeys = new HashMap<>();
            Set<ByteArrayWrapper> emptyKeys = new HashSet<>();
            CommittedWriterData wd = WriterData.getWriterData(owner, writer, mutable, storage).join();
            ChampWrapper<CborObject.CborMerkleLink> champ = ChampWrapper.create(owner, (Cid) wd.props.get().tree.get(),
                    Optional.empty(), x -> Futures.of(x.data), storage, hasher, b -> (CborObject.CborMerkleLink) b).join();
            champ.applyToAllMappings(owner, p -> {
                if (p.right.isPresent())
                    allKeys.put(p.left, p.right.get());
                else
                    emptyKeys.add(p.left);
                return Futures.of(true);
            }).join();

            Set<ByteArrayWrapper> unreachableKeys = new HashSet<>(allKeys.keySet());
            Optional<SigningPrivateKeyAndPublicHash> keypair = reachableKeys.keySet()
                    .stream()
                    .filter(s -> s.publicKeyHash.equals(writer))
                    .findFirst();
            if (keypair.isEmpty()) {
                if (namedOwnedKeys.contains(writer))
                    continue;
                // writing space is unreachable, but non-empty. Remove it by orphaning it.
                PublicKeyHash parent = toParent.get(writer);
                Optional<SigningPrivateKeyAndPublicHash> parentKeypair = reachableKeys.keySet()
                    .stream()
                    .filter(s -> s.publicKeyHash.equals(parent))
                    .findFirst();
                if (parentKeypair.isEmpty())
                    continue;
                CommittedWriterData pwd = WriterData.getWriterData(owner, parent, mutable, storage).join();
                c.network.synchronizer.applyComplexComputation(owner, parentKeypair.get(), (s, comm) -> {
                    TransactionId tid = storage.startTransaction(owner).join();
                    WriterData newPwd = pwd.props.get().removeOwnedKey(owner, parentKeypair.get(), writer, storage, hasher).join();
                    Snapshot updated = comm.commit(owner, parentKeypair.get(), newPwd, pwd, tid).join();
                    storage.closeTransaction(owner, tid).join();
                    return Futures.of(new Pair<>(updated, true));
                }).join();

                continue;
            }
            SigningPrivateKeyAndPublicHash signer = keypair.get();
            unreachableKeys.removeAll(reachableKeys.get(signer).values());

            if (! emptyKeys.isEmpty()) {
                c.network.synchronizer.applyComplexComputation(owner, signer, (s, comm) -> {
                    TransactionId tid = storage.startTransaction(owner).join();
                    WriterData current = wd.props.get();

                    for (ByteArrayWrapper key : emptyKeys) {
                        current = c.network.tree.remove(current, owner, signer, key.data, MaybeMultihash.empty(), tid).join();
                    }
                    Snapshot updated = comm.commit(owner, signer, current, wd, tid).join();
                    storage.closeTransaction(owner, tid).join();
                    return Futures.of(new Pair<>(updated, true));
                }).join();
            }

            if (unreachableKeys.isEmpty())
                continue;

            c.network.synchronizer.applyComplexComputation(owner, signer, (s, comm) -> {
                TransactionId tid = storage.startTransaction(owner).join();
                WriterData current = wd.props.get();

                for (ByteArrayWrapper key : unreachableKeys) {
                    current = c.network.tree.remove(current, owner, signer, key.data, MaybeMultihash.of(allKeys.get(key).target), tid).join();
                }
                Snapshot updated = comm.commit(owner, signer, current, wd, tid).join();
                storage.closeTransaction(owner, tid).join();
                return Futures.of(new Pair<>(updated, true));
            }).join();
        }
    }

    public static void checkRawUsage(UserContext c) {
        PublicKeyHash owner = c.signer.publicKeyHash;
        long serverCalculatedUsage = c.getSpaceUsage().join();
        Optional<BatWithId> mirror = c.getMirrorBat().join();
        Set<PublicKeyHash> writers = DeletableContentAddressedStorage.getOwnedKeysRecursive(owner, owner, c.network.mutable,
                (h, s) -> ContentAddressedStorage.getWriterData(owner, h, s, c.network.dhtClient), c.network.dhtClient, c.crypto.hasher).join();
        checkRawUsage(owner, writers, mirror, serverCalculatedUsage, c.network.dhtClient, c.network.mutable);
    }

    public static void checkRawUsage(PublicKeyHash owner,
                                     Set<PublicKeyHash> writers,
                                     Optional<BatWithId> mirror,
                                     long serverCalculatedUsage,
                                     ContentAddressedStorage storage,
                                     MutablePointers mutable) {
        Map<Cid, Long> blockSizes = new HashMap<>();
        Map<Cid, List<Cid>> linkedFrom = new HashMap<>();

        for (PublicKeyHash writer : writers) {
            getAllBlocksWithSize(owner, (Cid) mutable.getPointerTarget(owner, writer, storage).join().updated.get(),
                    mirror, storage, blockSizes, linkedFrom);
        }

        long totalFromBlocks = blockSizes.values().stream().mapToLong(i -> i).sum();
        if (totalFromBlocks != serverCalculatedUsage)
            throw new IllegalStateException("Incorrect usage! Expected: " + serverCalculatedUsage + ", actual: " + totalFromBlocks);
    }

    private static void getAllBlocksWithSize(PublicKeyHash owner,
                                             Cid root,
                                             Optional<BatWithId> mirror,
                                             ContentAddressedStorage dht,
                                             Map<Cid,  Long> res,
                                             Map<Cid, List<Cid>> linkedFrom) {
        Optional<byte[]> raw = dht.getRaw(owner, root, mirror).join();
        if (raw.isEmpty())
            return;
        byte[] block = raw.get();
        res.put(root, (long) block.length);
        if (! root.isRaw()) {
            List<Cid> children = CborObject.fromByteArray(block).links().stream().map(c -> (Cid) c).collect(Collectors.toList());
            for (Cid child : children) {
                if (child.isIdentity())
                    continue;
                linkedFrom.putIfAbsent(child, new ArrayList<>());
                linkedFrom.get(child).add(root);
                getAllBlocksWithSize(owner, child, mirror, dht, res, linkedFrom);
            }
        }
    }

    interface ChunkProcessor {
        boolean apply(SigningPrivateKeyAndPublicHash signer, AbsoluteCapability cap, String path, Optional<FileWrapper> f);
    }

    private static void traverseDescendants(FileWrapper dir,
                                            String path,
                                            ChunkProcessor visitor,
                                            UserContext c) {
        visitor.apply(dir.signingPair(), dir.writableFilePointer(), path, Optional.of(dir));
        Set<FileWrapper> children = dir.getChildren(c.crypto.hasher, c.network).join();
        for (FileWrapper child : children) {
            if (! child.isDirectory()) {
                WritableAbsoluteCapability cap = child.writableFilePointer();
                byte[] firstChunk = cap.getMapKey();
                Optional<Bat> firstBat = cap.bat;
                SigningPrivateKeyAndPublicHash childSigner = child.signingPair();
                visitor.apply(childSigner, cap, path + "/" + child.getName(), Optional.of(child));
                for (int i=0; i < child.getSize()/ (5*1024*1024); i++) {
                    byte[] streamSecret = child.getFileProperties().streamSecret.get();
                    Pair<byte[], Optional<Bat>> chunk = FileProperties.calculateMapKey(streamSecret, firstChunk,
                            firstBat, 5 * 1024 * 1024 * (i + 1), c.crypto.hasher).join();
                    visitor.apply(childSigner, cap.withMapKey(chunk.left, chunk.right), path + "/" + child.getName() + "[" + i + "]", Optional.empty());
                }
            } else
                traverseDescendants(child, path + "/" + child.getName(), visitor, c);
        }
    }
}
