package peergos.shared.user;
import java.util.logging.*;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.SymmetricKey;

import java.util.*;
import java.util.stream.*;

public class UserStaticData implements Cborable {
	private static final Logger LOG = Logger.getGlobal();
	private static final int PADDING = 8192;

    private final PaddedCipherText allEntryPoints;


    public UserStaticData(PaddedCipherText allEntryPoints) {
        this.allEntryPoints = allEntryPoints;
    }

    public UserStaticData(List<EntryPoint> staticData, SymmetricKey rootKey) {
        this(PaddedCipherText.build(rootKey, new EntryPoints(EntryPoints.VERSION, staticData), PADDING));
    }

    public UserStaticData(SymmetricKey rootKey) {
        this(new ArrayList<>(), rootKey);
    }

    public List<EntryPoint> getEntryPoints(SymmetricKey rootKey) {
        return allEntryPoints.decrypt(rootKey, EntryPoints::fromCbor).entries;
    }

    @Override
    public CborObject toCbor() {
        return allEntryPoints.toCbor();
    }

    public static UserStaticData fromCbor(Cborable cbor) {
        return new UserStaticData(PaddedCipherText.fromCbor(cbor));
    }

    private static class EntryPoints implements Cborable {
        private static final int VERSION = 0;

        private final long version;
        private final List<EntryPoint> entries;

        public EntryPoints(long version, List<EntryPoint> entries) {
            this.version = version;
            this.entries = entries;
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> res = new TreeMap<>();
            res.put("v", new CborObject.CborLong(VERSION));
            res.put("e", new CborObject.CborList(entries.stream()
                    .map(EntryPoint::toCbor)
                    .collect(Collectors.toList())));
            return CborObject.CborMap.build(res);
        }

        public static EntryPoints fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Incorrect cbor type for EntryPoints: " + cbor);
            CborObject.CborLong version = (CborObject.CborLong)((CborObject.CborMap) cbor).values.get(new CborObject.CborString("v"));
            if (version.value != VERSION)
                throw new IllegalStateException("Unknown UserStaticData version: " + version.value);
            return new EntryPoints(version.value,
                    ((CborObject.CborList) ((CborObject.CborMap) cbor).values.get(new CborObject.CborString("e")))
                            .value.stream()
                            .map(EntryPoint::fromCbor)
                            .collect(Collectors.toList()));
        }
    }
}
