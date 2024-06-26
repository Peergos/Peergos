package peergos.shared.crypto.asymmetric;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface PublicSigningKey extends Cborable {
    int MAX_SIZE = 10*1024*1024;

    Map<Integer, Type> byValue = new HashMap<>();
    @JsType
    enum Type {
        Ed25519(0x1);

        public final int value;

        Type(int value) {
            this.value = value;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            if (!byValue.containsKey(val))
                throw new IllegalStateException("Unknown public signing key type: " + ArrayOps.byteToHex(val));
            return byValue.get(val);
        }
    }

    Map<Type, Ed25519> PROVIDERS = new HashMap<>();

    static void addProvider(Type t, Ed25519 provider) {
        PROVIDERS.put(t, provider);
    }

    Type type();

    @JsMethod
    CompletableFuture<byte[]> unsignMessage(byte[] signed);

    static PublicSigningKey fromString(String b64) {
        return fromByteArray(Base64.getDecoder().decode(b64));
    }

    @JsMethod
    static PublicSigningKey fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    static PublicSigningKey fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for PublicSigningKey! " + cbor);
        CborObject.CborLong type = (CborObject.CborLong) ((CborObject.CborList) cbor).value.get(0);
        Type t = Type.byValue((int) type.value);
        switch (t) {
            case Ed25519:
                return Ed25519PublicKey.fromCbor(cbor, PROVIDERS.get(t));
            default:
                throw new IllegalStateException("Unknown Public Signing Key type: " + t.name());
        }
    }

    static PublicSigningKey createNull() {
        return new Ed25519PublicKey(new byte[32], PublicSigningKey.PROVIDERS.get(PublicSigningKey.Type.Ed25519));
    }
}
