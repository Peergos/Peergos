package peergos.shared.login.mfa;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
@JsType
public class MultiFactorAuthMethod implements Cborable {

    private static Map<Integer, MultiFactorAuthMethod.Type> byValue = new HashMap<>();

    @JsType
    public enum Type {
        TOTP(0x1, false),
        WEBAUTHN(0x2, true);

        public final int value;
        public final boolean hasChallenge;

        Type(int value, boolean hasChallengeValue) {
            this.value = value;
            this.hasChallenge = hasChallengeValue;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            return byValue.get(val);
        }
    }

    public final String name;
    public final byte[] credentialId;
    public final LocalDate created;
    public final Type type;
    public final boolean enabled;

    public MultiFactorAuthMethod(String name, byte[] credentialId, LocalDate created, Type type, boolean enabled) {
        if (name.length() > 32)
            throw new IllegalStateException("Second factor names must be smaller than 33 characters");
        this.name = name;
        this.credentialId = credentialId;
        this.created = created;
        this.type = type;
        this.enabled = enabled;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("n", new CborObject.CborString(name));
        state.put("i", new CborObject.CborByteArray(credentialId));
        state.put("c", new CborObject.CborLong(created.toEpochDay()));
        state.put("t", new CborObject.CborLong(type.value));
        state.put("e", new CborObject.CborBoolean(enabled));
        return CborObject.CborMap.build(state);
    }

    public static MultiFactorAuthMethod fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MultiFactorAuthMethod! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new MultiFactorAuthMethod(m.getString("n"),
                m.getByteArray("i"),
                LocalDate.ofEpochDay(m.getLong("c")),
                Type.byValue((int)m.getLong("t")),
                m.getBoolean("e"));
    }

    @Override
    public String toString() {
        return "MultiFactorAuthMethod{" +
                "uid='" + ArrayOps.bytesToHex(credentialId) + '\'' +
                ", type=" + type +
                ", enabled=" + enabled +
                '}';
    }
}
