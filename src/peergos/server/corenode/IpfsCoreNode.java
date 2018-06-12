package peergos.server.corenode;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class IpfsCoreNode implements CoreNode {

    private final ContentAddressedStorage ipfs;
    private final SigningPrivateKeyAndPublicHash signer;

    private final Map<String, List<UserPublicKeyLink>> chains = new ConcurrentHashMap<>();
    private final Map<PublicKeyHash, String> reverseLookup = new ConcurrentHashMap<>();
    private final List<String> usernames = new ArrayList<>();

    private MaybeMultihash currentRoot;

    public IpfsCoreNode(SigningPrivateKeyAndPublicHash signer, MaybeMultihash currentRoot, ContentAddressedStorage ipfs) {
        this.currentRoot = MaybeMultihash.empty();
        this.ipfs = ipfs;
        this.signer = signer;
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
                    // sign and publish this pki root
                    currentRoot = MaybeMultihash.of(newPkiRoot);
                    // save to disk, and publish to network
//                    todo
                    return CompletableFuture.completedFuture(true);
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
}
