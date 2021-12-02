package peergos.server.storage.auth;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multibase.*;

import java.util.*;

public class BlockAuth implements Cborable {

    public final byte[] signature;
    public final int expirySeconds;
    public final String awsDatetime;
    public final Cid batId;

    public BlockAuth(byte[] signature, int expirySeconds, String awsDatetime, Cid batId) {
        if (batId.isIdentity())
            throw new IllegalStateException("Cannot inline BAT in auth!");
        this.signature = signature;
        this.expirySeconds = expirySeconds;
        this.awsDatetime = awsDatetime;
        this.batId = batId;
    }

    public String shortDate() {
        return awsDatetime.substring(0, 8);
    }

    public String encode() { // TODO switch to byte[] auth in bitswap
        return Base58.encode(serialize());
    }

    public static BlockAuth fromString(String in) {
        if (in.isEmpty())
            throw new IllegalStateException("Empty block auth string!");
        return fromCbor(CborObject.fromByteArray(Base58.decode(in)));
    }

    private static long timeToPackedLong(String t) {
        int year = Integer.parseInt(t.substring(0, 4)) - 2000; // up to 38 bits
        int month = Integer.parseInt(t.substring(4, 6)); // 4 bits
        int day = Integer.parseInt(t.substring(6, 8)); // 5 bits
        int hour = Integer.parseInt(t.substring(9, 11)); // 5 bits
        int minute = Integer.parseInt(t.substring(11, 13)); // 6 bits
        int second = Integer.parseInt(t.substring(13, 15)); // 6 bits
        return second | (minute << 6) | (hour << 12) | (day << 17) | (month << 22) | (year << 26);
    }

    private static String packedLongToTime(long packed) {
        int year = (int) (packed >> 26) + 2000;
        int month = (int) (packed >> 22) & 0xF;
        int day = (int) (packed >> 17) & 0x1F;
        int hour = (int) (packed >> 12) & 0x1F;
        int minute = (int) (packed >> 6) & 0x3F;
        int second = (int) packed & 0x3F;
        return String.format("%04d%02d%02dT%02d%02d%02dZ", year, month, day, hour, minute, second);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("e", new CborObject.CborLong(expirySeconds));
        state.put("t", new CborObject.CborLong(timeToPackedLong(awsDatetime)));
        state.put("b", new CborObject.CborByteArray(batId.toBytes()));
        state.put("s", new CborObject.CborByteArray(signature));
        return CborObject.CborMap.build(state);
    }

    public static BlockAuth fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for BlockAuth: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new BlockAuth(m.getByteArray("s"), (int)m.getLong("e"), packedLongToTime(m.getLong("t")), Cid.cast(m.getByteArray("b")));
    }
}
