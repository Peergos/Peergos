package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;

import java.util.concurrent.*;

public class EncryptedCapability {
    public static final ScryptGenerator LINK_KEY_GENERATOR = new ScryptGenerator(15, 8, 1, 32, "");

    public final CipherText payload;

    public EncryptedCapability(CipherText payload) {
        this.payload = payload;
    }

    @JsMethod
    public CompletableFuture<AbsoluteCapability> decryptFromPassword(String salt, String password, Crypto c) {
        return deriveKey(salt, password, c)
                .thenApply(this::decrypt);
    }

    private AbsoluteCapability decrypt(SymmetricKey k) {
        return payload.decrypt(k, AbsoluteCapability::fromCbor);
    }

    private static CompletableFuture<SymmetricKey> deriveKey(String label, String password, Crypto c) {
        return c.hasher.hashToKeyBytes(label, password, LINK_KEY_GENERATOR)
                .thenApply(b -> new TweetNaClKey(b, false, c.symmetricProvider, c.random));
    }

    private static EncryptedCapability create(AbsoluteCapability raw, SymmetricKey k) {
        return new EncryptedCapability(CipherText.build(k, raw));
    }

    public static EncryptedCapability create(AbsoluteCapability raw) {
        SymmetricKey k = SymmetricKey.random();
        return create(raw, k);
    }

    @JsMethod
    public static CompletableFuture<EncryptedCapability> createFromPassword(AbsoluteCapability raw, String salt, String password, Crypto c) {
        return deriveKey(salt, password, c)
                .thenApply(k -> create(raw, k));
    }
}
