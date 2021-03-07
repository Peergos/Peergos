package peergos.shared.crypto;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.*;

public class ProofOfWork implements Cborable {
    public final static int PREFIX_BYTES = 8;
    public final static int MIN_DIFFICULTY = 0;
    public final static int DEFAULT_DIFFICULTY = 11;
    public final static int MAX_DIFFICULTY = 256;

    public final byte[] prefix;
    public final Multihash.Type type;

    public ProofOfWork(byte[] prefix, Multihash.Type type) {
        this.prefix = prefix;
        this.type = type;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> values = new TreeMap<>();
        values.put("prefix", new CborObject.CborByteArray(prefix));
        values.put("type", new CborObject.CborLong(type.index));
        return CborObject.CborMap.build(values);
    }

    public static ProofOfWork fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for ProofOfWork: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        byte[] prefix = map.getByteArray("prefix");
        int index = (int) map.getLong("type");
        return new ProofOfWork(prefix, Multihash.Type.lookup(index));
    }

    @JsMethod
    public static ProofOfWork buildSha256(byte[] prefix, byte[] data) {
        return new ProofOfWork(prefix, Multihash.Type.sha2_256);
    }

    /** This tests whether the calculated hash has at least *difficulty* leading zero bits
     *
     * @param difficulty
     * @param hash
     * @return
     */
    @JsMethod
    public static boolean satisfiesDifficulty(int difficulty, byte[] hash) {
        for (int i=0; i < difficulty; i+= 8) {
            if (i <= difficulty - 8) {
                if (hash[i/8] != 0)
                    return false;
            } else {
                int raw = hash[i / 8] & 0xFF;
                return (0xFF & (raw << (8 - difficulty + i))) == 0;
            }
        }
        return true;
    }

    public static ProofOfWork empty() {
        return new ProofOfWork(new byte[0], Multihash.Type.sha2_256);
    }
}
