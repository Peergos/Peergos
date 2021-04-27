package peergos.shared.messaging;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.*;

public class MessageRef implements Cborable {

    public final Multihash envelopeHash;

    public MessageRef(Multihash envelopeHash) {
        this.envelopeHash = envelopeHash;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("h", new CborObject.CborByteArray(envelopeHash.toBytes()));
        return CborObject.CborMap.build(result);
    }

    public static MessageRef fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Multihash h = Multihash.decode(m.getByteArray("h"));
        return new MessageRef(h);
    }
}
