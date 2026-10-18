package peergos.shared.routing;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.mlkem.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** A copy of a user's entry points, without the identity key, encrypted to their recovery keypair, so a client can
 *  write one whenever the entry points change without the recovery key, which stays offline. The home server keeps
 *  every copy, and only someone holding the recovery key can read them.
 */
public class RecoveryCopy implements Cborable {
    private static final int PADDING = 4096;

    public final PublicBoxingKey from;
    public final PaddedAsymmetricCipherText cipherText;

    public RecoveryCopy(PublicBoxingKey from, PaddedAsymmetricCipherText cipherText) {
        this.from = from;
        this.cipherText = cipherText;
    }

    public static CompletableFuture<RecoveryCopy> build(UserStaticData.EntryPoints entries, PublicBoxingKey to, Crypto crypto) {
        UserStaticData.EntryPoints withoutIdentity = entries.withIdentity(Optional.empty());
        return (to instanceof HybridCurve25519MLKEMPublicKey ?
                BoxingKeyPair.randomHybrid(crypto) :
                Futures.of(BoxingKeyPair.randomCurve25519(crypto.random, crypto.boxer)))
                .thenCompose(tmp -> PaddedAsymmetricCipherText.build(tmp.secretBoxingKey, to, withoutIdentity, PADDING)
                        .thenApply(cipher -> new RecoveryCopy(tmp.publicBoxingKey, cipher)));
    }

    public CompletableFuture<UserStaticData.EntryPoints> decrypt(BoxingKeyPair recovery) {
        return cipherText.decrypt(recovery.secretBoxingKey, from, UserStaticData.EntryPoints::fromCbor);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> m = new TreeMap<>();
        m.put("f", from);
        m.put("c", cipherText);
        return CborObject.CborMap.build(m);
    }

    public static RecoveryCopy fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "RecoveryCopy");
        return new RecoveryCopy(PublicBoxingKey.fromCbor(m.get("f")), PaddedAsymmetricCipherText.fromCbor(m.get("c")));
    }

    /** The recovery keypair, its secret encrypted with the key the recovery key derives, and its public key in the
     *  clear for clients to write copies to. It is written once, with the commitment it belongs to, so a thief who
     *  later holds the identity key cannot replace it.
     */
    public static class WrappedKey implements Cborable {
        public final PublicBoxingKey publicKey;
        public final PaddedCipherText cipherText;

        public WrappedKey(PublicBoxingKey publicKey, PaddedCipherText cipherText) {
            this.publicKey = publicKey;
            this.cipherText = cipherText;
        }

        public static WrappedKey wrap(BoxingKeyPair recovery, SymmetricKey wrapKey) {
            return new WrappedKey(recovery.publicBoxingKey, PaddedCipherText.build(wrapKey, recovery, PADDING));
        }

        public BoxingKeyPair unwrap(SymmetricKey wrapKey) {
            return cipherText.decrypt(wrapKey, BoxingKeyPair::fromCbor);
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("p", publicKey);
            m.put("c", cipherText);
            return CborObject.CborMap.build(m);
        }

        public static WrappedKey fromCbor(Cborable cbor) {
            CborObject.CborMap m = RoutingCbor.map(cbor, "RecoveryCopy.WrappedKey");
            return new WrappedKey(PublicBoxingKey.fromCbor(m.get("p")), PaddedCipherText.fromCbor(m.get("c")));
        }
    }
}
