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
    private static boolean isJava;

    public final SafeRandom random;
    public final LoginHasher hasher;
    public final Salsa20Poly1305 symmetricProvider;
    public final Ed25519 signer;
    public final Curve25519 boxer;

    public Crypto(SafeRandom random, LoginHasher hasher, Salsa20Poly1305 symmetricProvider, Ed25519 signer, Curve25519 boxer) {
        this.random = random;
        this.hasher = hasher;
        this.symmetricProvider = symmetricProvider;
        this.signer = signer;
        this.boxer = boxer;
    }

    private static synchronized Crypto init(Supplier<Crypto> instanceCreator, boolean isJava) {
        if (INSTANCE != null && Crypto.isJava ^ isJava)
            throw new IllegalStateException("Crypto is already initialized to a different type!");
        if (INSTANCE != null)
            return INSTANCE;
        Crypto instance = instanceCreator.get();
        INSTANCE = instance;
        Crypto.isJava = isJava;
        SymmetricKey.addProvider(SymmetricKey.Type.TweetNaCl, instance.symmetricProvider);
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, instance.signer);
        SymmetricKey.setRng(SymmetricKey.Type.TweetNaCl, instance.random);
        PublicBoxingKey.addProvider(PublicBoxingKey.Type.Curve25519, instance.boxer);
        PublicBoxingKey.setRng(PublicBoxingKey.Type.Curve25519, instance.random);
        return instance;
    }

    @JsMethod
    public static Crypto initJS() {
        SafeRandom.Java random = new SafeRandom.Java(); // TODO use nacl.randomBytes()
        Salsa20Poly1305.Java symmetricProvider = new Salsa20Poly1305.Java(); // TODO use nacl
        JavaEd25519 signer = new JavaEd25519(); // TODO use nacl
        JavaCurve25519 boxer = new JavaCurve25519(); // TODO use nacl
        return init(() -> new Crypto(random, new ScryptJS(), symmetricProvider, signer, boxer), false);
    }

    public static Crypto initJava() {
        SafeRandom.Java random = new SafeRandom.Java();
        Salsa20Poly1305.Java symmetricProvider = new Salsa20Poly1305.Java();
        JavaEd25519 signer = new JavaEd25519();
        JavaCurve25519 boxer = new JavaCurve25519();
        return init(() -> new Crypto(random, new ScryptJava(), symmetricProvider, signer, boxer), true);
    }
}
