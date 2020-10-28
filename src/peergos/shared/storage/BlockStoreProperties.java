package peergos.shared.storage;

import peergos.shared.cbor.*;

import java.util.*;

public class BlockStoreProperties implements Cborable {
    public final boolean directWrites, publicReads, authedReads;
    public final Optional<String> basePublicReadUrl;
    public final Optional<String> baseAuthedUrl;

    public BlockStoreProperties(boolean directWrites,
                                boolean publicReads,
                                boolean authedReads,
                                Optional<String> basePublicReadUrl,
                                Optional<String> baseAuthedUrl) {
        this.directWrites = directWrites;
        this.publicReads = publicReads;
        this.authedReads = authedReads;
        this.basePublicReadUrl = basePublicReadUrl;
        this.baseAuthedUrl = baseAuthedUrl;
    }

    public boolean useDirectBlockStore() {
        return directWrites || publicReads;
    }

    public static BlockStoreProperties empty() {
        return new BlockStoreProperties(false, false, false, Optional.empty(), Optional.empty());
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> props = new TreeMap<>();
        props.put("w", new CborObject.CborBoolean(directWrites));
        props.put("pr", new CborObject.CborBoolean(publicReads));
        props.put("ar", new CborObject.CborBoolean(authedReads));
        basePublicReadUrl.ifPresent(base -> props.put("b", new CborObject.CborString(base)));
        baseAuthedUrl.ifPresent(base -> props.put("ba", new CborObject.CborString(base)));
        return CborObject.CborMap.build(props);
    }

    public static BlockStoreProperties fromCbor(Cborable cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Optional<String> basePublic = map.getOptional("b", c -> ((CborObject.CborString)c).value);
        Optional<String> baseAuthed = map.getOptional("ba", c -> ((CborObject.CborString)c).value);
        boolean directWrites = map.getBoolean("w");
        boolean publicReads = map.getBoolean("pr");
        boolean authedReads = map.getBoolean("ar");
        return new BlockStoreProperties(directWrites, publicReads, authedReads, basePublic, baseAuthed);
    }
}
