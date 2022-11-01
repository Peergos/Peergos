package peergos.shared.user;
import java.util.logging.*;

import jsinterop.annotations.JsMethod;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.SymmetricKey;

import java.util.*;
import java.util.stream.*;

public class UserStaticData implements Cborable {
	private static final Logger LOG = Logger.getGlobal();
	private static final int PADDING_BLOCK_SIZE = 4096;

    private final PaddedCipherText allEntryPoints;

    public UserStaticData(PaddedCipherText allEntryPoints) {
        this.allEntryPoints = allEntryPoints;
    }

    public UserStaticData(EntryPoints login, SymmetricKey rootKey) {
        this(PaddedCipherText.build(rootKey, login, PADDING_BLOCK_SIZE));
    }

    public UserStaticData(List<EntryPoint> staticData,
                          SymmetricKey rootKey,
                          Optional<SigningKeyPair> identity,
                          Optional<BoxingKeyPair> boxer) {
        this(new EntryPoints(EntryPoints.CURRENT_VERSION, staticData, identity, boxer), rootKey);
    }

    public EntryPoints getData(SymmetricKey rootKey) {
        return allEntryPoints.decrypt(rootKey, EntryPoints::fromCbor);
    }

    @Override
    public CborObject toCbor() {
        return allEntryPoints.toCbor();
    }

    @JsMethod
    public static UserStaticData fromByteArray(byte[] entryPoints) {
        return UserStaticData.fromCbor(CborObject.fromByteArray(entryPoints));
    }

    public static UserStaticData fromCbor(Cborable cbor) {
        return new UserStaticData(PaddedCipherText.fromCbor(cbor));
    }

    public static class EntryPoints implements Cborable {
        private static final int CURRENT_VERSION = 2;

        private final long version;
        public final List<EntryPoint> entries;
        public final Optional<BoxingKeyPair> boxer; // this is only absent on legacy accounts
        public final Optional<SigningKeyPair> identity; // this is only absent on legacy accounts

        public EntryPoints(long version,
                           List<EntryPoint> entries,
                           Optional<SigningKeyPair> identity,
                           Optional<BoxingKeyPair> boxer) {
            this.version = version;
            this.entries = entries;
            this.identity = identity;
            this.boxer = boxer;
        }

        public EntryPoints addEntryPoint(EntryPoint entry) {
            List<EntryPoint> updated = Stream.concat(entries.stream(), Stream.of(entry)).collect(Collectors.toList());
            return new EntryPoints(version, updated, identity, boxer);
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> res = new TreeMap<>();
            res.put("v", new CborObject.CborLong(version));
            res.put("e", new CborObject.CborList(entries.stream()
                    .map(EntryPoint::toCbor)
                    .collect(Collectors.toList())));
            boxer.ifPresent(p -> res.put("b", p));
            identity.ifPresent(p -> res.put("i", p));
            return CborObject.CborMap.build(res);
        }

        public static EntryPoints fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Incorrect cbor type for EntryPoints: " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            long version = m.getLong("v");
            if (version > CURRENT_VERSION)
                throw new IllegalStateException("Unknown UserStaticData version: " + version);
            Optional<BoxingKeyPair> boxer = m.getOptional("b", BoxingKeyPair::fromCbor);
            Optional<SigningKeyPair> identity = m.getOptional("i", SigningKeyPair::fromCbor);
            return new EntryPoints(version,
                    m.getList("e")
                            .value.stream()
                            .map(EntryPoint::fromCbor)
                            .collect(Collectors.toList()),
                    identity,
                    boxer);
        }
    }
}
