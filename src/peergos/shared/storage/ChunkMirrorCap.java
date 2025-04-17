package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.io.ipfs.bases.Multibase;
import peergos.shared.storage.auth.BatWithId;

import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

public class ChunkMirrorCap implements Cborable {

    public final byte[] mapKey;
    public final Optional<BatWithId> bat;

    public ChunkMirrorCap(byte[] mapKey, Optional<BatWithId> bat) {
        this.mapKey = mapKey;
        this.bat = bat;
    }

    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("m", new CborObject.CborByteArray(mapKey));
        bat.ifPresent(b -> state.put("b", b));
        return CborObject.CborMap.build(state);
    }

    public static ChunkMirrorCap fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for ChunkMirrorCap: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new ChunkMirrorCap(m.get("m", c -> ((CborObject.CborByteArray)c).value),
                m.getOptional("b", BatWithId::fromCbor));
    }

    public String encodeToString() {
        return Multibase.encode(Multibase.Base.Base58BTC, toCbor().toByteArray());
    }

    public static ChunkMirrorCap fromString(String encoded) {
        return fromCbor(CborObject.fromByteArray(Multibase.decode(encoded)));
    }
}
