package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class TofuKeyStore implements Cborable {

    private final Map<String, List<UserPublicKeyLink>> chains;
    private final Map<String, List<UserPublicKeyLink>> expired;
    private final Map<PublicKeyHash, String> reverseLookup = new HashMap<>();

    public TofuKeyStore(Map<String, List<UserPublicKeyLink>> chains, Map<String, List<UserPublicKeyLink>> expired) {
        this.chains = chains;
        this.expired = expired;
        updateReverseLookup();
    }

    public TofuKeyStore() {
        this(new HashMap<>(), new HashMap<>());
    }

    public Optional<PublicKeyHash> getPublicKey(String username) {
        List<UserPublicKeyLink> chain = chains.get(username);
        if (chain == null) {
            List<UserPublicKeyLink> expiredChain = expired.get(username);
            if (expiredChain == null)
                return Optional.empty();
            return Optional.of(expiredChain.get(expiredChain.size() - 1).owner);
        }
        return Optional.of(chain.get(chain.size() - 1).owner);
    }

    public Optional<String> getUsername(PublicKeyHash signer) {
        String name = reverseLookup.get(signer);
        return Optional.ofNullable(name);
    }

    public List<UserPublicKeyLink> getChain(String username) {
        return chains.getOrDefault(username, expired.getOrDefault(username, Collections.emptyList()));
    }

    /**
     *
     * @param username
     * @param tail
     * @param ipfs
     * @return if there was any change
     */
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> tail, ContentAddressedStorage ipfs) {
        return UserPublicKeyLink.validChain(tail, username, ipfs)
                .thenCompose(valid -> {
                    if (!valid)
                        throw new IllegalStateException("Trying to update with invalid keychain!");
                    UserPublicKeyLink last = tail.get(tail.size() - 1);
                    // we are allowing expired chains to be stored

                    List<UserPublicKeyLink> existing = getChain(username);
                    if (! existing.isEmpty() && existing.get(existing.size() - 1).equals(tail.get(tail.size() - 1)))
                        return Futures.of(false);
                    boolean isExpired =
                            (existing.size() > 0 && UserPublicKeyLink.isExpiredClaim(existing.get(existing.size() - 1)));

                    CompletableFuture<List<UserPublicKeyLink>> mergedFuture;
                    if (isExpired) {
                        List<UserPublicKeyLink> withoutExpiredClaim = existing.subList(0, existing.size() - 1);
                        if (withoutExpiredClaim.size() == 0 &&
                                !Arrays.equals(existing.get(0).owner.toCbor().toByteArray(), tail.get(0).owner.toCbor().toByteArray()))
                            throw new IllegalStateException("Trying to update a username claim with a different key! "
                                    + ArrayOps.bytesToHex(existing.get(0).owner.toCbor().toByteArray()) + " != "
                                    + ArrayOps.bytesToHex(tail.get(0).owner.toCbor().toByteArray()));
                        expired.remove(username);
                    }
                    mergedFuture = UserPublicKeyLink.merge(existing, tail, ipfs);

                    return mergedFuture.thenApply(merged -> {
                        chains.put(username, merged);
                        PublicKeyHash owner = last.owner;
                        reverseLookup.put(owner, username);
                        return true;
                    });
                });
    }

    private void updateReverseLookup() {
        reverseLookup.clear();
        reverseLookup.putAll(
                Stream.concat(chains.entrySet().stream(), expired.entrySet().stream())
                        .collect(Collectors.toMap(
                                e -> e.getValue().get(e.getValue().size() - 1).owner,
                                e -> e.getKey())));
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        Consumer<Map<String, List<UserPublicKeyLink>>> serialise = map -> map.forEach((name, chain) -> state.put(name,
                new CborObject.CborList(chain.stream()
                        .map(link -> link.toCbor())
                        .collect(Collectors.toList()))));
        serialise.accept(chains);
        serialise.accept(expired);
        return CborObject.CborMap.build(state);
    }

    public static TofuKeyStore fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for Tofu key store: " + cbor);

        Map<String, List<UserPublicKeyLink>> chains = new HashMap<>();
        Map<String, List<UserPublicKeyLink>> expired = new HashMap<>();
        ((CborObject.CborMap) cbor).applyToAll((name, value) ->
        {
            if (value instanceof CborObject.CborList) {
                List<UserPublicKeyLink> chain = ((CborObject.CborList) value).value.stream()
                        .map(UserPublicKeyLink::fromCbor)
                        .collect(Collectors.toList());
                if (UserPublicKeyLink.isExpiredClaim(chain.get(chain.size() - 1)))
                    expired.put(name, chain);
                else
                    chains.put(name, chain);
            } else throw new IllegalStateException("Invalid value in Tofu key store map: " + value);
        });
        return new TofuKeyStore(chains, expired);
    }
}
