package peergos.shared.resolution;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** This is the object that can be resolved from a public key via one or more DHTs, or intermediaries
 *  It contains the information required to resolve a capability, even if the host is offline.
 *
 *  These are published by server's peerids, user's owner keypair, and writer key pairs.
 *  1. server ids
 *  Here this is used to rotate the server identity to a new key pair. Upon setup, a server generates its next identity.
 *  The next identity key pair can then be stored offline, and only used in case of server compromise.
 *  If a server becomes unreachable, or if the moved field is set to true then we attempt to resolve the new identity.
 *
 *  2. owner/writer key pairs
 *  This includes the current host peerid which queries can be directed to, and the current mutable pointer
 */
public class ResolutionRecord implements Cborable {
    public final Optional<Multihash> host; // For peer ids this can only be set, not removed or modified.
    public final boolean moved; // For peer ids this can only be updated from false to true
    public final Optional<byte[]> mutablePointer;
    public final long sequence; // monotonic, matches that in pointer if present

    public ResolutionRecord(Optional<Multihash> host,
                            boolean moved,
                            Optional<byte[]> mutablePointer,
                            long sequence) {
        this.host = host;
        this.moved = moved;
        this.mutablePointer = mutablePointer;
        this.sequence = sequence;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        host.ifPresent(h -> state.put("h", new CborObject.CborByteArray(h.toBytes())));
        if (moved)
            state.put("m", new CborObject.CborBoolean(true));
        mutablePointer.ifPresent(p -> state.put("p", new CborObject.CborByteArray(p)));
        state.put("s", new CborObject.CborLong(sequence));
        return CborObject.CborMap.build(state);
    }

    public static ResolutionRecord fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for ResolutionData! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Optional<Multihash> host = m.getOptionalByteArray("h").map(Multihash::decode);
        boolean moved = m.getBoolean("m");
        Optional<byte[]> mutablePointer = m.getOptionalByteArray("p");
        long seq = m.getLong("s");
        return new ResolutionRecord(host, moved, mutablePointer, seq);
    }
}
