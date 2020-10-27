package peergos.shared.storage;

import peergos.shared.cbor.*;

import java.util.*;

public class BlockStoreProperties implements Cborable {
    public final boolean directWrites, publicReads, authedReads;
    public final Optional<String> baseUrl;

    public BlockStoreProperties(boolean directWrites, boolean publicReads, boolean authedReads, Optional<String> baseUrl) {
        this.directWrites = directWrites;
        this.publicReads = publicReads;
        this.authedReads = authedReads;
        this.baseUrl = baseUrl;
    }

    public boolean useDirectBlockStore() {
        return directWrites || publicReads;
    }

    public static BlockStoreProperties empty() {
        return new BlockStoreProperties(false, false, false, Optional.empty());
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> props = new TreeMap<>();
        props.put("w", new CborObject.CborBoolean(directWrites));
        props.put("pr", new CborObject.CborBoolean(publicReads));
        props.put("ar", new CborObject.CborBoolean(authedReads));
        baseUrl.ifPresent(base -> props.put("b", new CborObject.CborString(base)));
        return CborObject.CborMap.build(props);
    }

    public static BlockStoreProperties fromCbor(Cborable cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Optional<String> base = map.getOptional("b", c -> ((CborObject.CborString)c).value);
        boolean directWrites = map.getBoolean("w");
        boolean publicReads = map.getBoolean("pr");
        boolean authedReads = map.getBoolean("ar");
        return new BlockStoreProperties(directWrites, publicReads, authedReads, base);
    }
}
