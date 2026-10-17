package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.util.*;

/** A server peerid is the libp2p identity multihash of its first server key, as libp2p and iroh name Ed25519
 *  keys, so existing peerids carry over unchanged.
 */
public class ServerKeys {
    private static final byte[] ED25519_PROTOBUF_PREFIX = {0x08, 0x01, 0x12, 0x20};

    public static Multihash peerId(PublicSigningKey server) {
        if (server.type() != PublicSigningKey.Type.Ed25519)
            throw new IllegalStateException("Server inceptions are Ed25519 until post-quantum peerids are defined");
        byte[] raw = ((CborObject.CborByteArray) ((CborObject.CborList) server.toCbor()).value.get(1)).value;
        return new Multihash(Multihash.Type.id, ArrayOps.concat(ED25519_PROTOBUF_PREFIX, raw));
    }
}
