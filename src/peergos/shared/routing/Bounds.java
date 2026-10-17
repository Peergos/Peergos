package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** remove-pki §4.1: a record over any bound is invalid. */
public class Bounds {
    public static final int MAX_PROVIDERS = 4;
    public static final int MAX_ADDRESSES = 8;
    public static final int MAX_ADDRESS_BYTES = 64;
    public static final int MAX_RELAYS = 3;
    public static final int MAX_TRANSPORT_NAME = 16;
    /** Excluding signatures, proof of work and keys, whose sizes are fixed by their type. */
    public static final int MAX_BODY_BYTES = 1024;
    public static final int MAX_PEERID_BYTES = 64;

    public static void checkProviders(List<Multihash> providers) {
        if (providers.isEmpty())
            throw new IllegalStateException("A record must name at least one provider");
        if (providers.size() > MAX_PROVIDERS)
            throw new IllegalStateException("Too many providers: " + providers.size());
        checkPeerIds(providers);
    }

    public static void checkPeerIds(List<Multihash> ids) {
        for (Multihash id : ids)
            if (id.toBytes().length > MAX_PEERID_BYTES)
                throw new IllegalStateException("Peerid too large");
        if (new HashSet<>(ids).size() != ids.size())
            throw new IllegalStateException("Duplicate peerids");
    }

    public static void checkBody(CborObject body, List<? extends Cborable> keys) {
        int size = body.toByteArray().length;
        for (Cborable key : keys)
            size -= key.serialize().length;
        if (size > MAX_BODY_BYTES)
            throw new IllegalStateException("Record body too large: " + size);
    }

    public static void checkCounter(long counter) {
        if (counter < 0)
            throw new IllegalStateException("Negative counter");
    }

    public static void checkCommitment(byte[] next) {
        if (next.length != 32)
            throw new IllegalStateException("A next-key commitment is a 32 byte hash");
    }
}
