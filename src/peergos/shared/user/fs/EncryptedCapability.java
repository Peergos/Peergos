package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.bases.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

public class EncryptedCapability implements Cborable {
    public static final ScryptGenerator LINK_KEY_GENERATOR = new ScryptGenerator(15, 8, 1, 32, "");

    public final CipherText payload;
    public final boolean hasUserPassword;

    public EncryptedCapability(CipherText payload, boolean hasUserPassword) {
        this.payload = payload;
        this.hasUserPassword = hasUserPassword;
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

    private static EncryptedCapability create(AbsoluteCapability raw, SymmetricKey k, boolean hasUserPassword) {
        return new EncryptedCapability(CipherText.build(k, raw), hasUserPassword);
    }

    @JsMethod
    public static CompletableFuture<EncryptedCapability> createFromPassword(AbsoluteCapability raw, String salt, String password, boolean hasUserPassword, Crypto c) {
        return deriveKey(salt, password, c)
                .thenApply(k -> create(raw, k, hasUserPassword));
    }

    private static int randomInt(int limit, SafeRandom r) {
        if (limit > 256)
            throw new IllegalStateException("Limit too large!");
        int val = r.randomBytes(1)[0] & 0xFF;
        if (val < limit)
            return val;
        return randomInt(limit, r);
    }
    private static final String passwordCharacters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    public static String createLinkPassword(SafeRandom r) {
        // want 12 characters from a-zA-Z0-9, so 62^12 ~ 2^72 possibilities,
        // or take 100 years to crack with 1M GPUs, each trying 1M scrypt hashes/S
        // any user supplied password is in addition to this
        String pw = "";
        for (int i=0; i < 12; i++)
            pw += passwordCharacters.charAt(randomInt(passwordCharacters.length(), r));
        return pw;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", payload.toCbor());
        if (hasUserPassword)
            state.put("p", new CborObject.CborBoolean(hasUserPassword));
        return CborObject.CborMap.build(state);
    }

    public static EncryptedCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for EncryptedCapability! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new EncryptedCapability(m.get("c", CipherText::fromCbor), m.getBoolean("p", false));
    }
}
