package peergos.server.corenode;
import java.util.logging.*;

import peergos.server.util.Logging;

import peergos.server.mutable.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class IpfsCoreNode implements CoreNode {
	private static final Logger LOG = Logging.LOG();

    public static final PublicSigningKey PEERGOS_IDENTITY_KEY = PublicSigningKey.fromString("ggFYIE7uD1ViM9KfiA1w69n774/jk6hERINN3xACPyabWiBp");
    public static final PublicKeyHash PEERGOS_IDENTITY_KEY_HASH = PublicKeyHash.fromString("zdpuAvZynWLuyvovJwa34bj24M7cspt5M8seFfrFLrPWDGFDW");

    private final PublicKeyHash peergosIdentity;
    private final ContentAddressedStorage ipfs;
    private final MutablePointers mutable;
    private final SigningPrivateKeyAndPublicHash signer;

    private final Map<String, List<UserPublicKeyLink>> chains = new ConcurrentHashMap<>();
    private final Map<PublicKeyHash, String> reverseLookup = new ConcurrentHashMap<>();
    private final List<String> usernames = new ArrayList<>();

    private MaybeMultihash currentRoot;

    public IpfsCoreNode(SigningKeyPair pkiKeys,
                        MaybeMultihash currentRoot,
                        ContentAddressedStorage ipfs,
                        MutablePointers mutable,
                        PublicKeyHash peergosIdentity) {
        this.currentRoot = MaybeMultihash.empty();
        this.ipfs = ipfs;
        this.mutable = mutable;
        this.peergosIdentity = peergosIdentity;
        PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiKeys.publicSigningKey);
        this.signer = new SigningPrivateKeyAndPublicHash(pkiPublicHash, pkiKeys.secretSigningKey);
        this.update(currentRoot);
    }

    public IpfsCoreNode(SigningKeyPair signer,
                        MaybeMultihash currentRoot,
                        ContentAddressedStorage ipfs,
                        MutablePointers mutable) {
        this(signer, currentRoot, ipfs, mutable, PEERGOS_IDENTITY_KEY_HASH);
    }

    /** Update the existing mappings based on the diff between the current champ and the champ with the supplied root.
     *
     * @param newRoot The root of the new champ
     */
    private synchronized void update(MaybeMultihash newRoot) {
        updateAllMappings(signer.publicKeyHash, currentRoot, newRoot, ipfs, chains, reverseLookup, usernames);
        this.currentRoot = newRoot;
    }

    public static void updateAllMappings(PublicKeyHash pkiSigner,
                                         MaybeMultihash currentChampRoot,
                                         MaybeMultihash newChampRoot,
                                         ContentAddressedStorage ipfs,
                                         Map<String, List<UserPublicKeyLink>> chains,
                                         Map<PublicKeyHash, String> reverseLookup,
                                         List<String> usernames) {
        try {
            CommittedWriterData current = WriterData.getWriterData(pkiSigner, currentChampRoot, ipfs).get();
            CommittedWriterData updated = WriterData.getWriterData(pkiSigner, newChampRoot, ipfs).get();
            MaybeMultihash currentTree = current.props.tree.map(MaybeMultihash::of).orElseGet(MaybeMultihash::empty);
            MaybeMultihash updatedTree = updated.props.tree.map(MaybeMultihash::of).orElseGet(MaybeMultihash::empty);
            Consumer<Triple<ByteArrayWrapper, MaybeMultihash, MaybeMultihash>> consumer =
                    t -> updateMapping(t.left, t.middle, t.right, ipfs, chains, reverseLookup, usernames);
            Champ.applyToDiff(currentTree, updatedTree, consumer, ipfs).get();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static void updateMapping(ByteArrayWrapper key,
                                     MaybeMultihash oldValue,
                                     MaybeMultihash newValue,
                                     ContentAddressedStorage ipfs,
                                     Map<String, List<UserPublicKeyLink>> chains,
                                     Map<PublicKeyHash, String> reverseLookup,
                                     List<String> usernames) {
        try {
            Optional<CborObject> cborOpt = ipfs.get(newValue.get()).get();
            if (!cborOpt.isPresent()) {
                LOG.severe("Couldn't retrieve new claim chain from " + newValue);
                return;
            }

            String username = new String(key.data);
            List<UserPublicKeyLink> updatedChain = ((CborObject.CborList) cborOpt.get()).value.stream()
                    .map(UserPublicKeyLink::fromCbor)
                    .collect(Collectors.toList());

            if (oldValue.isPresent()) {
                Optional<CborObject> existingCborOpt = ipfs.get(oldValue.get()).get();
                if (!existingCborOpt.isPresent()) {
                    LOG.severe("Couldn't retrieve existing claim chain from " + newValue);
                    return;
                }
                List<UserPublicKeyLink> existingChain = ((CborObject.CborList) existingCborOpt.get()).value.stream()
                        .map(UserPublicKeyLink::fromCbor)
                        .collect(Collectors.toList());
                // Check legality
                UserPublicKeyLink.merge(existingChain, updatedChain, ipfs).get();
            }
            PublicKeyHash owner = updatedChain.get(updatedChain.size() - 1).owner;

            reverseLookup.put(owner, username);
            chains.put(username, updatedChain);
            if (! oldValue.isPresent()) {
                // This is a new user
                usernames.add(username);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    /** Update a user's public key chain, keeping the in memory mappings correct and committing the new pki root
     *
     * @param username
     * @param updatedChain
     * @return
     */
    @Override
    public synchronized CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> updatedChain) {
            try {
                Function<ByteArrayWrapper, byte[]> identityHash = arr -> Arrays.copyOfRange(arr.data, 0, CoreNode.MAX_USERNAME_SIZE);
                CommittedWriterData current = WriterData.getWriterData(signer.publicKeyHash, currentRoot, ipfs).get();
                MaybeMultihash currentTree = current.props.tree.map(MaybeMultihash::of).orElseGet(MaybeMultihash::empty);

                ChampWrapper champ = currentTree.isPresent() ?
                        ChampWrapper.create(currentTree.get(), identityHash, ipfs).get() :
                        ChampWrapper.create(signer, identityHash, ipfs).get();
                MaybeMultihash existing = champ.get(username.getBytes()).get();
                Optional<CborObject> cborOpt = existing.isPresent() ?
                        ipfs.get(existing.get()).get() :
                        Optional.empty();
                if (! cborOpt.isPresent() && existing.isPresent()) {
                    LOG.severe("Couldn't retrieve existing claim chain from " + existing + " for " + username);
                    return CompletableFuture.completedFuture(true);
                }
                List<UserPublicKeyLink> existingChain = cborOpt.map(cbor -> ((CborObject.CborList) cbor).value.stream()
                        .map(UserPublicKeyLink::fromCbor)
                        .collect(Collectors.toList()))
                        .orElse(Collections.emptyList());

                List<UserPublicKeyLink> mergedChain = UserPublicKeyLink.merge(existingChain, updatedChain, ipfs).get();
                CborObject.CborList mergedChainCbor = new CborObject.CborList(mergedChain.stream()
                        .map(Cborable::toCbor)
                        .collect(Collectors.toList()));
                Multihash mergedChainHash = ipfs.put(signer, mergedChainCbor.toByteArray()).get();
                synchronized (this) {
                    Multihash newPkiRoot = champ.put(signer, username.getBytes(), existing, mergedChainHash).get();
                    return current.props.withChamp(newPkiRoot)
                            .commit(signer, currentRoot, mutable, ipfs, c -> {})
                            .thenApply(committed -> {
                                if (existingChain.isEmpty())
                                    usernames.add(username);
                                PublicKeyHash owner = updatedChain.get(updatedChain.size() - 1).owner;
                                reverseLookup.put(owner, username);
                                chains.put(username, mergedChain);
                                currentRoot = committed.hash;
                                return true;
                            });
                }
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
    }

    @Override
    public synchronized CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return CompletableFuture.completedFuture(chains.getOrDefault(username, Collections.emptyList()));
    }

    @Override
    public synchronized CompletableFuture<String> getUsername(PublicKeyHash key) {
        return CompletableFuture.completedFuture(reverseLookup.get(key));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return CompletableFuture.completedFuture(usernames);
    }

    @Override
    public void close() throws IOException {}

}
