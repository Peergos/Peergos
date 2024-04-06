package peergos.shared.display;

import jsinterop.annotations.*;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.fs.*;

import java.util.*;
@JsType
public class FileRef implements Cborable {
    public final String path;
    public final AbsoluteCapability cap;
    public final Multihash contentHash;

    @JsConstructor
    public FileRef(String path, AbsoluteCapability cap, Multihash contentHash) {
        if (path.contains("/../") || path.startsWith("../"))
            throw new IllegalStateException("Invalid path containing /../");
        this.path = path;
        this.cap = cap;
        this.contentHash = contentHash;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("p", new CborObject.CborString(path));
        state.put("c", cap);
        state.put("h", new CborObject.CborMerkleLink(contentHash));

        return CborObject.CborMap.build(state);
    }

    public String toJson() {
        return "{\"path\":\"" + path + "\", \"cap\":\"" + cap.toLink() + "\", \"contentHash\":\"" + contentHash.toString() + "\"}";
    }

    public static FileRef fromJson(String json) {
        Map<String, Object> jsonMap = (Map) JSONParser.parse(json);
        String path = (String) jsonMap.get("path");
        String capStr = (String) jsonMap.get("cap");
        String contentHashStr = (String) jsonMap.get("contentHash");
        AbsoluteCapability cap = AbsoluteCapability.fromLink(capStr);
        Multihash contentHash = Multihash.fromBase58(contentHashStr);
        return new FileRef(path, cap, contentHash);
    }

    public static FileRef fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        String path = m.getString("p");
        AbsoluteCapability cap = m.get("c", AbsoluteCapability::fromCbor);
        Multihash contentHash = m.getMerkleLink("h");
        return new FileRef(path, cap, contentHash);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileRef ref = (FileRef) o;
        return Objects.equals(path, ref.path) && Objects.equals(cap, ref.cap) && Objects.equals(contentHash, ref.contentHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, cap, contentHash);
    }
}
