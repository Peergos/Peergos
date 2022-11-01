package peergos.shared.crypto.symmetric;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.random.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public interface SymmetricKey extends Cborable
{
    Map<Integer, Type> byValue = new HashMap<>();
    enum Type {
        TweetNaCl(0x1);

        public final int value;

        Type(int value) {
            this.value = value;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            return byValue.get(val);
        }
    }

    Map<Type, Salsa20Poly1305> PROVIDERS = new HashMap<>();

    Map<Type, SafeRandom> RNG_PROVIDERS = new HashMap<>();

    static void addProvider(Type t, Salsa20Poly1305 provider) {
        PROVIDERS.put(t, provider);
    }

    static void setRng(Type t, SafeRandom rng) {
        RNG_PROVIDERS.put(t, rng);
    }

    Type type();

    byte[] getKey();

    @JsMethod
    byte[] encrypt(byte[] data, byte[] nonce);

    @JsMethod
    byte[] decrypt(byte[] data, byte[] nonce);

    @JsMethod
    byte[] createNonce();

    @JsMethod
    boolean isDirty();

    @JsMethod
    SymmetricKey makeDirty();

    @JsMethod
    static SymmetricKey fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    @JsMethod
    default byte[] toByteArray() {
        return serialize();
    }

    static SymmetricKey fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for PublicBoxingKey! " + cbor);
        CborObject.CborLong type = (CborObject.CborLong) ((CborObject.CborList) cbor).value.get(0);
        Type t = Type.byValue((int) type.value);
        switch (t) {
            case TweetNaCl:
                return TweetNaClKey.fromCbor(cbor, PROVIDERS.get(t), RNG_PROVIDERS.get(t));
            default: throw new IllegalStateException("Unknown Symmetric Key type: "+t.name());
        }
    }

    @JsMethod
    static SymmetricKey random() {
        return TweetNaClKey.random(PROVIDERS.get(Type.TweetNaCl), RNG_PROVIDERS.get(Type.TweetNaCl));
    }

    static SymmetricKey createNull() {
        return new TweetNaClKey(new byte[TweetNaClKey.KEY_BYTES], false, PROVIDERS.get(Type.TweetNaCl), RNG_PROVIDERS.get(Type.TweetNaCl));
    }
}
