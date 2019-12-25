package peergos.shared.crypto.asymmetric;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.random.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public interface PublicBoxingKey extends Cborable {
    Map<Integer, Type> byValue = new HashMap<>();
    enum Type {
        Curve25519(0x1);

        public final int value;
        Type(int value)
        {
            this.value = value;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            if (!byValue.containsKey(val))
                throw new IllegalStateException("Unknown public boxing key type: " + ArrayOps.byteToHex(val));
            return byValue.get(val);
        }
    }

    Map<Type, Curve25519> PROVIDERS = new HashMap<>();

    static void addProvider(Type t, Curve25519 provider) {
        PROVIDERS.put(t, provider);
    }

    Map<Type, SafeRandom> RNG_PROVIDERS = new HashMap<>();

    static void setRng(Type t, SafeRandom rng) {
        RNG_PROVIDERS.put(t, rng);
    }

    Type type();

    @JsMethod
    byte[] getPublicBoxingKey();

    @JsMethod
    byte[] encryptMessageFor(byte[] input, SecretBoxingKey from);

    @JsMethod
    byte[] createNonce();

    @JsMethod
    static PublicBoxingKey fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    static PublicBoxingKey fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for PublicBoxingKey! " + cbor);
        CborObject.CborLong type = (CborObject.CborLong) ((CborObject.CborList) cbor).value.get(0);
        Type t = Type.byValue((int) type.value);
        switch (t) {
            case Curve25519:
                return Curve25519PublicKey.fromCbor(cbor, PROVIDERS.get(t), RNG_PROVIDERS.get(t));
            default:
                throw new IllegalStateException("Unknown Public Boxing Key type: " + t.name());
        }
    }
}
