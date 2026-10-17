package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.stream.*;

/** Peerids are encoded as plain multihash bytes, never as cbor tags. */
class RoutingCbor {

    static CborObject.CborList peerIds(List<Multihash> ids) {
        return new CborObject.CborList(ids.stream()
                .map(id -> new CborObject.CborByteArray(id.toBytes()))
                .collect(Collectors.toList()));
    }

    static List<Multihash> peerIds(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for peerids: " + cbor);
        return ((CborObject.CborList) cbor).value.stream()
                .map(c -> Multihash.decode(((CborObject.CborByteArray) c).value))
                .collect(Collectors.toList());
    }

    static CborObject.CborMap map(Cborable cbor, String type) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for " + type + ": " + cbor);
        return (CborObject.CborMap) cbor;
    }
}
