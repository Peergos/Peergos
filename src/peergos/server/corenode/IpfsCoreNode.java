package peergos.server.corenode;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

import peergos.server.util.Logging;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class IpfsCoreNode implements CoreNode {
	private static final Logger LOG = Logging.LOG();

    private final PublicKeyHash peergosIdentity;
    private final ContentAddressedStorage ipfs;
    private final MutablePointers mutable;
    private final SigningPrivateKeyAndPublicHash signer;

    private final Map<String, List<UserPublicKeyLink>> chains = new ConcurrentHashMap<>();
    private final Map<PublicKeyHash, String> reverseLookup = new ConcurrentHashMap<>();
    private final List<String> usernames = new ArrayList<>();

    private MaybeMultihash currentRoot;

    public IpfsCoreNode(SigningPrivateKeyAndPublicHash pkiSigner,
                        MaybeMultihash currentRoot,
                        ContentAddressedStorage ipfs,
                        MutablePointers mutable,
                        PublicKeyHash peergosIdentity) {
        this.currentRoot = MaybeMultihash.empty();
        this.ipfs = ipfs;
        this.mutable = mutable;
        this.peergosIdentity = peergosIdentity;
        this.signer = pkiSigner;
        this.update(currentRoot);
    }

    public static byte[] keyHash(ByteArrayWrapper username) {
        return Blake2b.Digest.newInstance().digest(username.data);
    }

    /** Update the existing mappings based on the diff between the current champ and the champ with the supplied root.
     *
     * @param newRoot The root of the new champ
     */
    private synchronized void update(MaybeMultihash newRoot) {
        updateAllMappings(signer.publicKeyHash, currentRoot, newRoot, ipfs, chains, reverseLookup, usernames);
        this.currentRoot = newRoot;
    }

    private static MaybeMultihash getTreeRoot(MaybeMultihash pointerTarget, ContentAddressedStorage ipfs) {
        if (! pointerTarget.isPresent())
            return MaybeMultihash.empty();
        CommittedWriterData current = WriterData.getWriterData(pointerTarget.get(), ipfs).join();
        return current.props.tree.map(MaybeMultihash::of).orElseGet(MaybeMultihash::empty);

    }

    public static void updateAllMappings(PublicKeyHash pkiSigner,
                                         MaybeMultihash currentChampRoot,
                                         MaybeMultihash newChampRoot,
                                         ContentAddressedStorage ipfs,
                                         Map<String, List<UserPublicKeyLink>> chains,
                                         Map<PublicKeyHash, String> reverseLookup,
                                         List<String> usernames) {
        try {
            MaybeMultihash currentTree = getTreeRoot(currentChampRoot, ipfs);
            MaybeMultihash updatedTree = getTreeRoot(newChampRoot, ipfs);
            Consumer<Triple<ByteArrayWrapper, MaybeMultihash, MaybeMultihash>> consumer =
                    t -> updateMapping(t.left, t.middle, t.right, ipfs, chains, reverseLookup, usernames);
            Champ.applyToDiff(currentTree, updatedTree, 0, IpfsCoreNode::keyHash,
                    Collections.emptyList(), Collections.emptyList(),
                    consumer, ChampWrapper.BIT_WIDTH, ipfs).get();
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

            List<UserPublicKeyLink> updatedChain = ((CborObject.CborList) cborOpt.get()).value.stream()
                    .map(UserPublicKeyLink::fromCbor)
                    .collect(Collectors.toList());

            String username = new String(key.data);

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
        if (! UsernameValidator.isValidUsername(username))
            throw new IllegalStateException("Invalid username");

        try {
            CommittedWriterData current = WriterData.getWriterData(currentRoot.get(), ipfs).get();
            MaybeMultihash currentTree = current.props.tree.map(MaybeMultihash::of).orElseGet(MaybeMultihash::empty);

            ChampWrapper champ = currentTree.isPresent() ?
                    ChampWrapper.create(currentTree.get(), IpfsCoreNode::keyHash, ipfs).get() :
                    IpfsTransaction.call(peergosIdentity,
                            tid -> ChampWrapper.create(signer.publicKeyHash, signer, IpfsCoreNode::keyHash, tid, ipfs),
                            ipfs).get();
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
            Multihash mergedChainHash = IpfsTransaction.call(peergosIdentity,
                    tid -> ipfs.put(peergosIdentity, signer, mergedChainCbor.toByteArray(), tid),
                    ipfs).get();
            synchronized (this) {
                return IpfsTransaction.call(peergosIdentity,
                        tid -> champ.put(signer.publicKeyHash, signer, username.getBytes(), existing, mergedChainHash, tid)
                                .thenCompose(newPkiRoot -> current.props.withChamp(newPkiRoot)
                                        .commit(peergosIdentity, signer, currentRoot, mutable, ipfs, tid)),
                        ipfs
                ).thenApply(committed -> {
                    if (existingChain.isEmpty())
                        usernames.add(username);
                    PublicKeyHash owner = updatedChain.get(updatedChain.size() - 1).owner;
                    reverseLookup.put(owner, username);
                    chains.put(username, mergedChain);
                    currentRoot = committed.get(signer).hash;
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
        return CompletableFuture.completedFuture(Optional.ofNullable(reverseLookup.get(key))
                .orElseThrow(() -> new IllegalStateException("Unknown identity key: " + key)));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return CompletableFuture.completedFuture(usernames);
    }

    @Override
    public void close() throws IOException {}

}
