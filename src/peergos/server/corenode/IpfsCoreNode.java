package peergos.server.corenode;

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

    public static final PublicSigningKey PEERGOS_IDENTITY_KEY = PublicSigningKey.fromString("ggFYIE7uD1ViM9KfiA1w69n774/jk6hERINN3xACPyabWiBp");
    public static final PublicKeyHash PEERGOS_IDENTITY_KEY_HASH = PublicKeyHash.fromString("zdpuAvZynWLuyvovJwa34bj24M7cspt5M8seFfrFLrPWDGFDW");

    private final ContentAddressedStorage ipfs;
    private final MutablePointers mutable;
    private final SigningPrivateKeyAndPublicHash signer;

    private final Map<String, List<UserPublicKeyLink>> chains = new ConcurrentHashMap<>();
    private final Map<PublicKeyHash, String> reverseLookup = new ConcurrentHashMap<>();
    private final List<String> usernames = new ArrayList<>();

    private MaybeMultihash currentRoot;

    public IpfsCoreNode(SigningPrivateKeyAndPublicHash signer,
                        MaybeMultihash currentRoot,
                        ContentAddressedStorage ipfs,
                        MutablePointers mutable) {
        this.currentRoot = MaybeMultihash.empty();
        this.ipfs = ipfs;
        this.signer = signer;
        this.mutable = mutable;
        this.update(currentRoot);
    }

    private synchronized void update(MaybeMultihash newRoot) {
        Consumer<Triple<ByteArrayWrapper, MaybeMultihash, MaybeMultihash>> consumer =
                triple -> {
                    ByteArrayWrapper key = triple.left;
                    MaybeMultihash oldValue = triple.middle;
                    MaybeMultihash newValue = triple.right;
                    try {
                        Optional<CborObject> cborOpt = ipfs.get(newValue.get()).get();
                        if (!cborOpt.isPresent()) {
                            System.err.println("Couldn't retrieve new claim chain from " + newValue);
                            return;
                        }

                        String username = new String(key.data);
                        List<UserPublicKeyLink> updatedChain = ((CborObject.CborList) cborOpt.get()).value.stream()
                                .map(UserPublicKeyLink::fromCbor)
                                .collect(Collectors.toList());

                        if (oldValue.isPresent()) {
                            Optional<CborObject> existingCborOpt = ipfs.get(oldValue.get()).get();
                            if (!existingCborOpt.isPresent()) {
                                System.err.println("Couldn't retrieve existing claim chain from " + newValue);
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
                        e.printStackTrace();
                    }
                };
        try {
            Champ.applyToDiff(currentRoot, newRoot, consumer, ipfs).get();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> updatedChain) {
            try {
                Function<ByteArrayWrapper, byte[]> identityHash = arr -> Arrays.copyOfRange(arr.data, 0, CoreNode.MAX_USERNAME_SIZE);
                ChampWrapper champ = ChampWrapper.create(currentRoot.get(), identityHash, ipfs).get();
                MaybeMultihash existing = champ.get(username.getBytes()).get();
                Optional<CborObject> cborOpt = ipfs.get(existing.get()).get();
                if (! cborOpt.isPresent() && existing.isPresent()) {
                    System.err.println("Couldn't retrieve existing claim chain from " + existing + " for " + username);
                    return CompletableFuture.completedFuture(true);
                }
                List<UserPublicKeyLink> existingChain = ((CborObject.CborList) cborOpt.get()).value.stream()
                        .map(UserPublicKeyLink::fromCbor)
                        .collect(Collectors.toList());

                List<UserPublicKeyLink> mergedChain = UserPublicKeyLink.merge(existingChain, updatedChain, ipfs).get();
                CborObject.CborList mergedChainCbor = new CborObject.CborList(mergedChain.stream()
                        .map(Cborable::toCbor)
                        .collect(Collectors.toList()));
                Multihash mergedChainHash = ipfs.put(signer, mergedChainCbor.toByteArray()).get();
                synchronized (this) {
                    Multihash newPkiRoot = champ.put(signer, username.getBytes(), existing, mergedChainHash).get();
                    // commit new root, and publish signed cas to network
                    HashCasPair cas = new HashCasPair(currentRoot, MaybeMultihash.of(newPkiRoot));
                    currentRoot = MaybeMultihash.of(newPkiRoot);
                    byte[] signedCas = signer.secret.signMessage(cas.serialize());
                    return mutable.setPointer(PEERGOS_IDENTITY_KEY_HASH, signer.publicKeyHash, signedCas);
                }
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return CompletableFuture.completedFuture(chains.get(username));
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        return CompletableFuture.completedFuture(reverseLookup.get(key));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return CompletableFuture.completedFuture(usernames);
    }

    @Override
    public void close() throws IOException {}

    public static void main(String[] args) throws Exception {
        // This method will add a named (pki) key to the peergos user,
        // load the existing pki into a champ and store the root of that champ under the named pki key
        // The pki key is independent of the 'peergos' user identity key

        Crypto crypto = Crypto.initJava();
        NetworkAccess network = NetworkAccess.buildJava(new URL("https://demo.peergos.net")).get();
        NonWriteThroughMutablePointers tmpMutable = new NonWriteThroughMutablePointers(network.mutable, network.dhtClient);
        Console console = System.console();
        String user = "peergos";
        String password = new String(console.readPassword("Enter password for " + user + ":"));
        Pair<Multihash, CborObject> pair = UserContext.getWriterDataCbor(network, user).get();
        Optional<UserGenerationAlgorithm> algorithmOpt = WriterData.extractUserGenerationAlgorithm(pair.right);
        if (!algorithmOpt.isPresent())
            throw new IllegalStateException("No login algorithm specified in user data!");
        UserGenerationAlgorithm algorithm = algorithmOpt.get();
        UserWithRoot owner = UserUtil.generateUser(user, password, crypto.hasher, crypto.symmetricProvider,
                crypto.random, crypto.signer, crypto.boxer, algorithm).get();
        WriterData ownerProperties = WriterData.fromCbor(pair.right, owner.getRoot());
        SigningPrivateKeyAndPublicHash ownerIdentity = new SigningPrivateKeyAndPublicHash(ownerProperties.controller, owner.getUser().secretSigningKey);

        String pkiPassword = new String(console.readPassword("Enter password for pki:"));
        UserWithRoot pkiKeys = UserUtil.generateUser(user, pkiPassword, crypto.hasher, crypto.symmetricProvider,
                crypto.random, crypto.signer, crypto.boxer, new ScryptEd25519Curve25519(ScryptEd25519Curve25519.MIN_MEMORY_COST, 8, 1, 96)).get();

        // ensure the user has the owned key for the pki
        PublicSigningKey pkiPublicKey = pkiKeys.getUser().publicSigningKey;
        PublicKeyHash pkiPublicHash = network.dhtClient.putSigningKey(
                ownerIdentity.secret.signatureOnly(pkiPublicKey.serialize()),
                ownerProperties.controller,
                pkiPublicKey).get();
        if (! ownerProperties.ownedKeys.contains(pkiPublicHash)) {
            WriterData withKey = ownerProperties.addNamedKey("pki", pkiPublicHash);
            withKey.commit(ownerIdentity, MaybeMultihash.of(pair.left), network, x -> {}).get();
        }

        SigningPrivateKeyAndPublicHash pkiSigner = new SigningPrivateKeyAndPublicHash(pkiPublicHash, pkiKeys.getUser().secretSigningKey);

        MaybeMultihash priorChampRoot = network.mutable.getPointerTarget(pkiPublicHash, network.dhtClient).get();
        IpfsCoreNode target = new IpfsCoreNode(pkiSigner, priorChampRoot, network.dhtClient, tmpMutable);
        List<String> usernames = network.coreNode.getUsernames("").get();
        for (String username : usernames) {
            target.updateChain(username, network.coreNode.getChain(username).get()).get();
        }
        System.out.println("Final PKI root: " + target.currentRoot);

        // finally update the mutable pointer to the new champ
        HashCasPair cas = new HashCasPair(priorChampRoot, target.currentRoot);
        byte[] signed = pkiSigner.secret.signMessage(cas.serialize());
        network.mutable.setPointer(ownerProperties.controller, pkiPublicHash, signed).get();
    }
}
