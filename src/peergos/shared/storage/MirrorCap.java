package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.auth.*;

import java.util.*;

public class MirrorCap implements Cborable {

    public final Cid hash;
    public final Optional<BatWithId> bat;

    public MirrorCap(Cid hash, Optional<BatWithId> bat) {
        this.hash = hash;
        this.bat = bat;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("h", new CborObject.CborByteArray(hash.toBytes()));
        bat.ifPresent(b -> state.put("b", b));
        return CborObject.CborMap.build(state);
    }

    public static MirrorCap fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for MirrorCap: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new MirrorCap(m.get("h", c -> Cid.cast(((CborObject.CborByteArray)c).value)),
                m.getOptional("b", BatWithId::fromCbor));
    }
}
