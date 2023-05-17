package peergos.shared.login.mfa;

import peergos.shared.cbor.*;

import java.util.*;

public class MultiFactorAuthMethod implements Cborable {

    private static Map<Integer, MultiFactorAuthMethod.Type> byValue = new HashMap<>();

    public enum Type {
        TOTP(0x1, false),
        U2F(0x2, true);

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

    public final String uid;
    public final Type type;
    public final boolean enabled;

    public MultiFactorAuthMethod(String uid, Type type, boolean enabled) {
        this.uid = uid;
        this.type = type;
        this.enabled = enabled;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("u", new CborObject.CborString(uid));
        state.put("t", new CborObject.CborLong(type.value));
        state.put("e", new CborObject.CborBoolean(enabled));
        return CborObject.CborMap.build(state);
    }

    public static MultiFactorAuthMethod fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MultiFactorAuthMethod! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new MultiFactorAuthMethod(m.getString("uid"), Type.byValue((int)m.getLong("t")), m.getBoolean("e"));
    }

    @Override
    public String toString() {
        return "MultiFactorAuthMethod{" +
                "uid='" + uid + '\'' +
                ", type=" + type +
                ", enabled=" + enabled +
                '}';
    }
}
