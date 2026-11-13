package peergos.shared.mutable;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.mldsa.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** The key a writer's pointer is signed with.
 *
 *  An Ed25519 writer upgrades in place to a composite key with the same Ed25519 half, keeping its hash. The
 *  composite key is named as the controller of the WriterData the pointer targets, and once a pointer is signed
 *  with it every later one must be.
 */
public class PointerKeys {
    private static final LRUCache<Cid, PublicKeyHash> controllers = new LRUCache<>(10_000);

    /** Ed25519 signed pointers are under 150 bytes, composite ones over 2400 */
    public static boolean isCompositeSigned(byte[] signedPointer) {
        return signedPointer.length >= HybridEd25519MLDSAPublicKey.SIGNATURE_SIZE_BYTES;
    }

    public static CompletableFuture<PublicSigningKey> pointerKey(PublicKeyHash owner,
                                                                 PublicKeyHash writer,
                                                                 byte[] signed,
                                                                 ContentAddressedStorage ipfs) {
        return pointerKey(owner, writer, signed, ipfs, Collections.emptyMap());
    }

    /**
     * @param pending blocks that aren't stored yet, like the rest of a bulk commit, by hash
     */
    public static CompletableFuture<PublicSigningKey> pointerKey(PublicKeyHash owner,
                                                                 PublicKeyHash writer,
                                                                 byte[] signed,
                                                                 ContentAddressedStorage ipfs,
                                                                 Map<Cid, byte[]> pending) {
        return key(owner, writer, ipfs, pending).thenCompose(base -> {
            if (base.type() != PublicSigningKey.Type.Ed25519 || ! isCompositeSigned(signed))
                return Futures.of(base);
            Optional<Cid> root = claimedRoot(signed);
            if (root.isEmpty())
                return Futures.errored(new IllegalStateException("Composite signed pointer with no WriterData for " + writer));
            return controller(owner, root.get(), ipfs, pending)
                    .thenCompose(controller -> key(owner, controller, ipfs, pending))
                    .thenApply(upgraded -> {
                        if (! (upgraded instanceof HybridEd25519MLDSAPublicKey) ||
                                ! ((HybridEd25519MLDSAPublicKey) upgraded).ed25519.equals(base))
                            throw new IllegalStateException("WriterData controller isn't an upgrade of " + writer);
                        return upgraded;
                    });
        });
    }

    /** The WriterData a composite signed pointer says names its key, before checking the signature */
    public static Optional<Cid> claimedRoot(byte[] signed) {
        if (! isCompositeSigned(signed))
            return Optional.empty();
        PointerUpdate claimed = PointerUpdate.fromCbor(CborObject.fromByteArray(
                Arrays.copyOfRange(signed, HybridEd25519MLDSAPublicKey.SIGNATURE_SIZE_BYTES, signed.length)));
        MaybeMultihash root = claimed.updated.isPresent() ? claimed.updated : claimed.original;
        return root.toOptional().map(h -> (Cid) h);
    }

    public static CompletableFuture<PointerUpdate> verify(PublicKeyHash owner,
                                                          PublicKeyHash writer,
                                                          byte[] signed,
                                                          ContentAddressedStorage ipfs) {
        return verify(owner, writer, signed, ipfs, Collections.emptyMap());
    }

    public static CompletableFuture<PointerUpdate> verify(PublicKeyHash owner,
                                                          PublicKeyHash writer,
                                                          byte[] signed,
                                                          ContentAddressedStorage ipfs,
                                                          Map<Cid, byte[]> pending) {
        return pointerKey(owner, writer, signed, ipfs, pending)
                .thenCompose(key -> decode(key, signed));
    }

    public static CompletableFuture<PointerUpdate> decode(PublicSigningKey key, byte[] signed) {
        return key.unsignMessage(signed, SigningDomain.POINTER)
                .thenApply(raw -> PointerUpdate.fromCbor(CborObject.fromByteArray(raw)));
    }

    /** Check a proposed pointer against the current one: its signature, the CAS and sequence, and that an
     *  upgraded writer stays upgraded.
     *
     * @return the key that signed the proposed pointer, and what it says
     */
    public static CompletableFuture<Pair<PublicSigningKey, PointerUpdate>> checkUpdate(PublicKeyHash owner,
                                                                                    PublicKeyHash writer,
                                                                                    Optional<byte[]> current,
                                                                                    byte[] proposed,
                                                                                    ContentAddressedStorage ipfs,
                                                                                    Map<Cid, byte[]> pending) {
        CompletableFuture<Optional<Pair<PublicSigningKey, PointerUpdate>>> existing = current
                .map(c -> pointerKey(owner, writer, c, ipfs)
                        .thenCompose(k -> decode(k, c).thenApply(u -> Optional.of(new Pair<>(k, u)))))
                .orElse(Futures.of(Optional.empty()));
        return existing.thenCompose(now -> pointerKey(owner, writer, proposed, ipfs, pending)
                .thenCompose(key -> decode(key, proposed)
                        // the CAS first, so a writer that missed an upgrade merges onto it
                        .thenCompose(update -> MutablePointers.isValidUpdate(now.map(p -> p.right), update.original, update.sequence)
                                .thenApply(x -> {
                                    checkTransition(writer, now.map(p -> p.left), key);
                                    return new Pair<>(key, update);
                                }))));
    }

    /** An upgrade can't be undone or replaced */
    public static void checkTransition(PublicKeyHash writer, Optional<PublicSigningKey> current, PublicSigningKey proposed) {
        if (current.isPresent() && current.get().type() != PublicSigningKey.Type.Ed25519 && ! current.get().equals(proposed))
            throw new IllegalStateException("Pointer for " + writer + " must stay signed by its composite key");
    }

    /** Whether WriterData with this controller can be the target of a pointer for writer signed by key */
    public static boolean isValidController(PublicKeyHash writer, PublicKeyHash controller, PublicSigningKey key) {
        return controller.equals(writer) ||
                (key instanceof HybridEd25519MLDSAPublicKey && controller.equals(ContentAddressedStorage.hashKey(key)));
    }

    private static CompletableFuture<PublicKeyHash> controller(PublicKeyHash owner,
                                                               Cid writerData,
                                                               ContentAddressedStorage ipfs,
                                                               Map<Cid, byte[]> pending) {
        synchronized (controllers) {
            PublicKeyHash cached = controllers.get(writerData);
            if (cached != null)
                return Futures.of(cached);
        }
        byte[] inCall = pending.get(writerData);
        return (inCall != null ?
                Futures.of(Optional.of(CborObject.fromByteArray(inCall))) :
                ipfs.get(owner, writerData, Optional.empty()))
                .thenApply(cbor -> {
                    if (cbor.isEmpty())
                        throw new IllegalStateException("Couldn't retrieve WriterData " + writerData);
                    PublicKeyHash controller = WriterData.fromCbor(cbor.get()).controller;
                    synchronized (controllers) {
                        controllers.put(writerData, controller);
                    }
                    return controller;
                });
    }

    private static CompletableFuture<PublicSigningKey> key(PublicKeyHash owner,
                                                           PublicKeyHash hash,
                                                           ContentAddressedStorage ipfs,
                                                           Map<Cid, byte[]> pending) {
        if (! hash.isIdentity()) {
            byte[] inCall = pending.get(hash.target);
            if (inCall != null) {
                PublicSigningKey key = PublicSigningKey.fromByteArray(inCall);
                if (ContentAddressedStorage.hashKey(key).equals(hash))
                    return Futures.of(key);
            }
        }
        return ipfs.getSigningKey(owner, hash)
                .thenApply(opt -> opt.orElseThrow(() -> new IllegalStateException("Couldn't retrieve signing key " + hash)));
    }
}
