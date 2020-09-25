package peergos.shared;

import jsinterop.annotations.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;

import java.util.function.*;

public class Crypto {

    private static Crypto INSTANCE;

    @JsProperty
    public final SafeRandom random;
    public final Hasher hasher;
    public final Salsa20Poly1305 symmetricProvider;
    public final Ed25519 signer;
    public final Curve25519 boxer;

    public Crypto(SafeRandom random, Hasher hasher, Salsa20Poly1305 symmetricProvider, Ed25519 signer, Curve25519 boxer) {
        this.random = random;
        this.hasher = hasher;
        this.symmetricProvider = symmetricProvider;
        this.signer = signer;
        this.boxer = boxer;
    }

    public static synchronized Crypto init(Supplier<Crypto> instanceCreator) {
        if (INSTANCE != null)
            return INSTANCE;
        Crypto instance = instanceCreator.get();
        INSTANCE = instance;
        SymmetricKey.addProvider(SymmetricKey.Type.TweetNaCl, instance.symmetricProvider);
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, instance.signer);
        SymmetricKey.setRng(SymmetricKey.Type.TweetNaCl, instance.random);
        PublicBoxingKey.addProvider(PublicBoxingKey.Type.Curve25519, instance.boxer);
        PublicBoxingKey.setRng(PublicBoxingKey.Type.Curve25519, instance.random);
        return instance;
    }

    @JsMethod
    public static Crypto initJS() {
        SafeRandom.Javascript random = new SafeRandom.Javascript();
        Salsa20Poly1305.Javascript symmetricProvider = new Salsa20Poly1305.Javascript();
        Ed25519.Javascript signer = new Ed25519.Javascript();
        Curve25519.Javascript boxer = new Curve25519.Javascript();
        return init(() -> new Crypto(random, new ScryptJS(), symmetricProvider, signer, boxer));
    }
}
