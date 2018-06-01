package peergos.server.corenode;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class CoreNodeSnapshot {

    private final Map<String, List<UserPublicKeyLink>> chains;
    private final Map<PublicKeyHash, String> reverseLookup;
    private final List<String> usernames;

    public CoreNodeSnapshot(Map<String, List<UserPublicKeyLink>> chains) {
        this.chains = chains;
        this.reverseLookup = buildReverseLookup(chains);
        this.usernames = new ArrayList<>(chains.keySet());
    }

    private static Map<PublicKeyHash, String> buildReverseLookup(Map<String, List<UserPublicKeyLink>> chains) {
        Map<PublicKeyHash, String> res = new HashMap<>();
        for (Map.Entry<String, List<UserPublicKeyLink>> entry : chains.entrySet()) {
            res.put(entry.getValue().get(entry.getValue().size() - 1).owner, entry.getKey());
        }
        return res;
    }

    public static CoreNodeSnapshot buildFromRoot(Multihash root, ContentAddressedStorage ipfs) throws Exception {
        ChampWrapper champ = ChampWrapper.create(root, arr -> Arrays.copyOfRange(arr.data, 0, CoreNode.MAX_USERNAME_SIZE), ipfs).get();
        Map<String, List<UserPublicKeyLink>> chains = new TreeMap<>();
        champ.applyToAllMappings(true, (b, p) ->
                ! p.right.isPresent() ?
                        CompletableFuture.completedFuture(true) :
                        ipfs.get(p.right.get())
                                .thenApply(cborOpt -> cborOpt.map(cbor -> chains.put(new String(p.left.data),
                                        ((CborObject.CborList)cbor).value.stream().map(UserPublicKeyLink::fromCbor).collect(Collectors.toList())))
                                        .map(x -> true).orElse(true)
                                )
        );
        return new CoreNodeSnapshot(chains);
    }
}
