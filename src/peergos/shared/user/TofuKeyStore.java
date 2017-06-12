package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class TofuKeyStore implements Cborable {

    private final Map<String, List<UserPublicKeyLink>> chains;
    private final Map<String, List<UserPublicKeyLink>> expired;
    private final Map<PublicSigningKey, String> reverseLookup = new HashMap<>();

    public TofuKeyStore(Map<String, List<UserPublicKeyLink>> chains, Map<String, List<UserPublicKeyLink>> expired) {
        this.chains = chains;
        this.expired = expired;
        updateReverseLookup();
    }

    public TofuKeyStore() {
        this(new HashMap<>(), new HashMap<>());
    }

    public Optional<PublicSigningKey> getPublicKey(String username) {
        List<UserPublicKeyLink> chain = chains.get(username);
        if (chain == null) {
            List<UserPublicKeyLink> expiredChain = expired.get(username);
            if (expiredChain == null)
                return Optional.empty();
            return Optional.of(expiredChain.get(expiredChain.size() - 1).owner);
        }
        return Optional.of(chain.get(chain.size() - 1).owner);
    }

    public Optional<String> getUsername(PublicSigningKey signer) {
        String name = reverseLookup.get(signer);
        return Optional.ofNullable(name);
    }

    public List<UserPublicKeyLink> getChain(String username) {
        return chains.getOrDefault(username, expired.getOrDefault(username, Collections.emptyList()));
    }

    public void updateChain(String username, List<UserPublicKeyLink> tail) {
        if (! UserPublicKeyLink.validChain(tail, username))
            throw new IllegalStateException("Trying to update with invalid keychain!");
        UserPublicKeyLink last = tail.get(tail.size() - 1);
        // we are allowing expired chains to be stored

        List<UserPublicKeyLink> existing = getChain(username);
        boolean existingExpired =
                (existing.size() > 0 && UserPublicKeyLink.isExpiredClaim(existing.get(existing.size() - 1))) ||
                        expired.containsKey(username);
        if (existingExpired) {
            List<UserPublicKeyLink> expiredChain = expired.getOrDefault(username, existing);
            List<UserPublicKeyLink> withoutExpiredClaim = expiredChain.subList(0, expiredChain.size() - 1);
            if (withoutExpiredClaim.size() == 0 && ! expiredChain.get(0).owner.toCbor().equals(tail.get(0).owner.toCbor()))
                throw new IllegalStateException("Trying to update a username claim with a different key!");

            List<UserPublicKeyLink> merged = UserPublicKeyLink.merge(withoutExpiredClaim, tail);
            chains.put(username, merged);
            expired.remove(username);
            PublicSigningKey owner = last.owner;
            reverseLookup.put(owner, username);
        } else {
            List<UserPublicKeyLink> merged = UserPublicKeyLink.merge(existing, tail);
            chains.put(username, merged);
            PublicSigningKey owner = last.owner;
            reverseLookup.put(owner, username);
        }
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
        SortedMap<CborObject, CborObject> state = new TreeMap<>();
        Consumer<Map<String, List<UserPublicKeyLink>>> serialise = map -> map.forEach((name, chain) -> state.put(new CborObject.CborString(name),
                new CborObject.CborList(chain.stream()
                        .map(link -> link.toCbor())
                        .collect(Collectors.toList()))));
        serialise.accept(chains);
        serialise.accept(expired);
        return new CborObject.CborMap(state);
    }

    public static TofuKeyStore fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for Tofu key store: " + cbor);

        Map<String, List<UserPublicKeyLink>> chains = new HashMap<>();
        Map<String, List<UserPublicKeyLink>> expired = new HashMap<>();
        SortedMap<CborObject, CborObject> values = ((CborObject.CborMap) cbor).values;
        for (CborObject key: values.keySet()) {
            if (key instanceof CborObject.CborString) {
                String name = ((CborObject.CborString) key).value;
                CborObject value = values.get(key);
                if (value instanceof CborObject.CborList) {
                    List<UserPublicKeyLink> chain = ((CborObject.CborList) value).value.stream()
                            .map(UserPublicKeyLink::fromCbor)
                            .collect(Collectors.toList());
                    if (UserPublicKeyLink.validChain(chain, name)) {
                        if (UserPublicKeyLink.isExpiredClaim(chain.get(chain.size() - 1)))
                            expired.put(name, chain);
                        else
                            chains.put(name, chain);
                    }
                } else throw new IllegalStateException("Invalid value in Tofu key store map: " + value);
            } else throw new IllegalStateException("Invalid key in Tofu key store map: " + key);
        }
        return new TofuKeyStore(chains, expired);
    }
}
