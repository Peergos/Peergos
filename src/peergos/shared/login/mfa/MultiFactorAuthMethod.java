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
        TOTP(0x1, false, true),
        WEBAUTHN(0x2, true, true),
        BACKUP_CODES(0x3, false, true),
        /** A code based factor belonging to a device mount rather than to a person. Generated the
         *  same way as a TOTP, but never offered to a client that a human logs in through - a mount
         *  holds its own secret and answers the challenge itself. */
        MOUNT(0x4, false, false);

        public final int value;
        public final boolean hasChallenge;
        /** Whether a client driven by a human should be offered this as a way of logging in. */
        public final boolean interactive;

        Type(int value, boolean hasChallengeValue, boolean interactive) {
            this.value = value;
            this.hasChallenge = hasChallengeValue;
            this.interactive = interactive;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            Type res = byValue.get(val);
            if (res == null)
                throw new IllegalStateException("Unknown second factor type: " + val
                        + ". Please update to a newer version.");
            return res;
        }
    }

    public static final int MAX_NAME_LENGTH = 32;

    public final String name;
    public final byte[] credentialId;
    public final LocalDate created;
    public final Type type;
    public final boolean enabled;

    public MultiFactorAuthMethod(String name, byte[] credentialId, LocalDate created, Type type, boolean enabled) {
        if (name.length() > MAX_NAME_LENGTH)
            throw new IllegalStateException("Second factor names must be smaller than " + (MAX_NAME_LENGTH + 1) + " characters");
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
