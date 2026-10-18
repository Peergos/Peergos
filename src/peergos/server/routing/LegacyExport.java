package peergos.server.routing;

import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.resolution.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.function.*;
import java.util.logging.*;

/** Builds the PKI's final state on the PKI node, to be pinned (remove-pki §4.1). */
public class LegacyExport {
    private static final Logger LOG = Logging.LOG();
    private static final int MAX_ROTATIONS = 16;

    /** The RSA rotations the client already trusts without a signature, because it has no RSA verifier. */
    public static final List<LegacyState.ServerRotation> KNOWN = List.of(
            new LegacyState.ServerRotation(Multihash.fromBase58("QmPqn9a1tJLpMtaCz1DSQNMAfsv6qXEx6XU2eLMTc2DVV4"),
                    Multihash.fromBase58("12D3KooWEnCzE4uSeniFaCGXQuV1UnYkvqvbQJnYC363S2abgknr"), Optional.empty()),
            new LegacyState.ServerRotation(Multihash.fromBase58("QmcoDbhCiVXGrWs6rwBvB59Gm44veo7Qxn2zmRnPw7BaCH"),
                    Multihash.fromBase58("12D3KooWFv6ZcoUKyaDBB7nR5SQg6HpmEbDXad48WyFSyEk7xrSR"), Optional.empty()));

    /** The pinned WriterData, and the rotations of every server a last claim names, followed to the end. */
    public static LegacyState build(Cid pkiWriterData,
                                    Map<String, List<UserPublicKeyLink>> chains,
                                    Function<Multihash, Optional<IpnsEntry>> ipns) {
        Set<Multihash> providers = new TreeSet<>(Comparator.comparing(Multihash::toString));
        for (List<UserPublicKeyLink> chain : chains.values())
            for (Multihash p : chain.get(chain.size() - 1).claim.storageProviders)
                providers.add(p.bareMultihash());
        Map<Multihash, LegacyState.ServerRotation> rotations = new LinkedHashMap<>();
        for (Multihash provider : providers) {
            Multihash at = provider;
            for (int i = 0; i < MAX_ROTATIONS && ! rotations.containsKey(at); i++) {
                Optional<LegacyState.ServerRotation> r = rotation(at, ipns);
                if (r.isEmpty())
                    break;
                rotations.put(at, r.get());
                at = r.get().to;
            }
        }
        for (LegacyState.ServerRotation known : KNOWN)
            if (providers.contains(known.from) || rotations.values().stream().anyMatch(r -> r.to.equals(known.from)))
                rotations.putIfAbsent(known.from, known);
        return new LegacyState(pkiWriterData, new ArrayList<>(rotations.values()));
    }

    private static Optional<LegacyState.ServerRotation> rotation(Multihash server, Function<Multihash, Optional<IpnsEntry>> ipns) {
        try {
            Optional<IpnsEntry> entry = ipns.apply(server);
            if (entry.isEmpty())
                return Optional.empty();
            ResolutionRecord value = entry.get().getValue();
            if (! value.moved || value.host.isEmpty())
                return Optional.empty();
            return Optional.of(new LegacyState.ServerRotation(server, value.host.get(), entry));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Couldn't read the IPNS record of " + server + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
