package peergos.server.routing;

import peergos.server.corenode.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.resolution.*;
import peergos.shared.routing.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.logging.*;
import java.util.stream.*;

/** Imports the PKI's final state as legacy bases, once (remove-pki §4.1). Legacy records are valid only inside
 *  the pinned state, so they are never gossiped: every node imports the same state, and anything else claiming to
 *  be legacy is not.
 */
public class LegacyImport {
    private static final Logger LOG = Logging.LOG();
    public static final String META_KEY = "legacy-state";

    public static class Result {
        public final int identities, servers, invalid;

        public Result(int identities, int servers, int invalid) {
            this.identities = identities;
            this.servers = servers;
            this.invalid = invalid;
        }
    }

    public static Cid hash(byte[] state, Crypto crypto) {
        return Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.sha2_256, crypto.hasher.sha256(state).join());
    }

    /** Reads every chain in the PKI's champ as of the pinned WriterData, from local storage or the given servers,
     *  the pki node typically, for blocks this server's mirror of the PKI lacks.
     */
    public static Map<String, List<UserPublicKeyLink>> chains(LegacyState state,
                                                              PublicKeyHash pkiOwner,
                                                              List<Multihash> sources,
                                                              DeletableContentAddressedStorage storage,
                                                              Crypto crypto) {
        Map<String, List<UserPublicKeyLink>> chains = new HashMap<>();
        Cid ourId = storage.id().join();
        List<Multihash> peers = new ArrayList<>(sources);
        peers.add(ourId);
        IpfsCoreNode.updateAllMappings(peers, pkiOwner, MaybeMultihash.empty(), MaybeMultihash.of(state.pki),
                ourId, crypto.hasher, storage, chains, new HashMap<>(), new ArrayList<>());
        return chains;
    }

    /** Imports the state if it is the pinned one and has not been imported already. */
    public static Optional<Result> run(byte[] rawState,
                                       Cid pinned,
                                       Map<String, List<UserPublicKeyLink>> chains,
                                       ContentAddressedStorage keys,
                                       RoutingReplica replica,
                                       Crypto crypto) {
        JdbcRoutingStore store = replica.store();
        if (store.getMeta(META_KEY).map(v -> Arrays.equals(v, pinned.toBytes())).orElse(false))
            return Optional.empty();
        if (store.getMeta(META_KEY).isPresent())
            throw new IllegalStateException("A different legacy state was imported already");
        if (! hash(rawState, crypto).equals(pinned))
            throw new IllegalStateException("Legacy state does not match the pinned hash " + pinned);
        LegacyState state = LegacyState.fromCbor(CborObject.fromByteArray(rawState));

        List<JdbcRoutingStore.Legacy> entries = new ArrayList<>();
        int invalid = 0;
        for (String username : chains.keySet().stream().sorted().collect(Collectors.toList())) {
            Optional<LegacyIdentity> identity = validate(username, chains.get(username), keys);
            if (identity.isEmpty()) {
                invalid++;
                continue;
            }
            LegacyIdentity id = identity.get();
            entries.add(new JdbcRoutingStore.Legacy(Subject.identity(id.identity()), id, new ArrayList<>(id.owners)));
        }
        int identities = entries.size();
        for (LegacyServer server : servers(state, crypto)) {
            entries.add(new JdbcRoutingStore.Legacy(Subject.server(server.first()), server, server.peerIds));
        }
        store.putLegacy(entries);
        store.setMeta(META_KEY, pinned.toBytes());
        replica.clearCache();
        Result res = new Result(identities, entries.size() - identities, invalid);
        LOG.info("Imported legacy state " + pinned + ": " + res.identities + " identities, " + res.servers +
                " server rotations, " + res.invalid + " invalid chains skipped");
        return Optional.of(res);
    }

    /** The PKI's own rules for a chain: each claim signed by its owner for this username, each owner signing
     *  over the next, and one provider, on the last claim. Owner keys must be inline in their hashes.
     */
    public static Optional<LegacyIdentity> validate(String username, List<UserPublicKeyLink> chain, ContentAddressedStorage keys) {
        try {
            if (chain.isEmpty() || chain.stream().anyMatch(l -> ! l.owner.isIdentity()))
                return Optional.empty();
            if (! UserPublicKeyLink.validChain(chain, username, keys).join())
                return Optional.empty();
            UserPublicKeyLink last = chain.get(chain.size() - 1);
            PublicSigningKey current = PublicSigningKey.fromCbor(CborObject.fromByteArray(last.owner.getHash()));
            List<PublicKeyHash> owners = chain.stream().map(l -> l.owner).collect(Collectors.toList());
            return Optional.of(new LegacyIdentity(username, owners, current, last.claim.storageProviders));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Invalid legacy chain for " + username + ": " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Follows the pinned rotations into chains of peerids. A record, where kept, must say the same as the pin. A
     *  peerid rotating to two others, or a cycle, is not a chain, and is dropped.
     */
    public static List<LegacyServer> servers(LegacyState state, Crypto crypto) {
        Map<Multihash, Multihash> next = new HashMap<>();
        Set<Multihash> forked = new HashSet<>();
        for (LegacyState.ServerRotation r : state.servers) {
            if (r.record.isPresent() && ! recordAgrees(r, crypto)) {
                LOG.warning("Legacy server rotation from " + r.from + " disagrees with its IPNS record");
                continue;
            }
            if (next.containsKey(r.from) && ! next.get(r.from).equals(r.to))
                forked.add(r.from);
            next.put(r.from, r.to);
        }
        forked.forEach(next::remove);
        Set<Multihash> targets = new HashSet<>(next.values());
        List<LegacyServer> res = new ArrayList<>();
        for (Multihash head : next.keySet().stream()
                .filter(f -> ! targets.contains(f))
                .sorted(Comparator.comparing(m -> new ByteArrayWrapper(m.toBytes())))
                .collect(Collectors.toList())) {
            List<Multihash> chain = new ArrayList<>();
            Set<Multihash> seen = new HashSet<>();
            for (Multihash at = head; at != null && seen.add(at); at = next.get(at))
                chain.add(at);
            res.add(new LegacyServer(chain));
        }
        return res;
    }

    private static boolean recordAgrees(LegacyState.ServerRotation r, Crypto crypto) {
        try {
            ResolutionRecord value = r.from.isIdentity() ?
                    r.record.get().getValue(r.from, crypto).join() :
                    r.record.get().getValue();
            return value.moved && value.host.map(h -> h.bareMultihash().equals(r.to)).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }
}
