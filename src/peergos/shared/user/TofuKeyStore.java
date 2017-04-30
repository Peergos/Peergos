package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.*;
import java.util.stream.*;

public class TofuKeyStore implements Cborable {

    private final Map<String, List<UserPublicKeyLink>> chains;

    public TofuKeyStore(Map<String, List<UserPublicKeyLink>> chains) {
        this.chains = chains;
    }

    public Optional<PublicSigningKey> getPublicKey(String username) {
        List<UserPublicKeyLink> chain = chains.get(username);
        if (chain == null)
            return Optional.empty();
        return Optional.of(chain.get(chain.size() - 1).owner);
    }

    public List<UserPublicKeyLink> getChain(String username) {
        return chains.getOrDefault(username, Collections.emptyList());
    }

    public void updateChain(String username, List<UserPublicKeyLink> tail) {
        UserPublicKeyLink.validChain(tail, username);

        if (tail.size() > 2)
            throw new IllegalStateException("Cannot update key chain by more than a single link at once!");

        List<UserPublicKeyLink> existing = getChain(username);
        List<UserPublicKeyLink> merged = UserPublicKeyLink.merge(existing, tail);
        chains.put(username, merged);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<CborObject, CborObject> state = new TreeMap<>();
        chains.forEach((name, chain) -> state.put(new CborObject.CborString(name),
                new CborObject.CborList(chain.stream()
                        .map(link -> link.toCbor())
                        .collect(Collectors.toList()))));
        return new CborObject.CborMap(state);
    }

    public static TofuKeyStore fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for Tofy key store: " + cbor);

        Map<String, List<UserPublicKeyLink>> chains = new HashMap<>();
        SortedMap<CborObject, CborObject> values = ((CborObject.CborMap) cbor).values;
        for (CborObject key: values.keySet()) {
            if (key instanceof CborObject.CborString) {
                String name = ((CborObject.CborString) key).value;
                CborObject value = values.get(key);
                if (value instanceof CborObject.CborList) {
                    List<UserPublicKeyLink> chain = ((CborObject.CborList) value).value.stream()
                            .map(UserPublicKeyLink::fromCbor)
                            .collect(Collectors.toList());
                    UserPublicKeyLink.validChain(chain, name);
                    chains.put(name, chain);
                } else throw new IllegalStateException("Invalid value in Tofu key store map: " + value);
            } else throw new IllegalStateException("Invalid key in Tofu key store map: " + key);
        }
        return new TofuKeyStore(chains);
    }
}
