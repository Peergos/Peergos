package peergos.shared.user.fs;

import peergos.shared.Crypto;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.storage.auth.Bat;

import java.util.SortedMap;
import java.util.TreeMap;

public class ResumeUploadProps implements Cborable {
    public final SymmetricKey baseKey, dataKey, writeKey;
    public final byte[] streamSecret;
    public final Bat firstChunkBat;
    public final byte[] firstChunkMapKey;

    public ResumeUploadProps(SymmetricKey baseKey,
                             SymmetricKey dataKey,
                             SymmetricKey writeKey,
                             byte[] streamSecret,
                             Bat firstChunkBat,
                             byte[] firstChunkMapKey) {
        this.baseKey = baseKey;
        this.dataKey = dataKey;
        this.writeKey = writeKey;
        this.streamSecret = streamSecret;
        this.firstChunkBat = firstChunkBat;
        this.firstChunkMapKey = firstChunkMapKey;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("b", baseKey.toCbor());
        state.put("d", dataKey.toCbor());
        state.put("w", writeKey.toCbor());
        state.put("s", new CborObject.CborByteArray(streamSecret));
        state.put("ib", firstChunkBat.toCbor());
        state.put("m", new CborObject.CborByteArray(firstChunkMapKey));
        return CborObject.CborMap.build(state);
    }

    public static ResumeUploadProps fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for PartialUploadProps! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        SymmetricKey baseKey = m.get("b", SymmetricKey::fromCbor);
        SymmetricKey dataKey = m.get("d", SymmetricKey::fromCbor);
        SymmetricKey writeKey = m.get("w", SymmetricKey::fromCbor);
        byte[] streamSecret = m.getByteArray("s");
        Bat initialBat = m.get("ib", Bat::fromCbor);
        byte[] initialMapKey = m.getByteArray("m");
        return new ResumeUploadProps(baseKey, dataKey, writeKey, streamSecret, initialBat, initialMapKey);
    }

    public static ResumeUploadProps random(Crypto crypto) {
        SymmetricKey baseKey = SymmetricKey.random();
        SymmetricKey dataKey = SymmetricKey.random();
        SymmetricKey writeKey = SymmetricKey.random();
        byte[] streamSecret = crypto.random.randomBytes(32);
        Bat firstChunkBat = Bat.random(crypto.random);
        byte[] firstChunkMapKey = crypto.random.randomBytes(32);
        return new ResumeUploadProps(baseKey, dataKey, writeKey, streamSecret, firstChunkBat, firstChunkMapKey);
    }
}
