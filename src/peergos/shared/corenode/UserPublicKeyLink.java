package peergos.shared.corenode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class UserPublicKeyLink implements Cborable {
    public static final int MAX_SIZE = 2*1024*1024;
    public static final int MAX_USERNAME_SIZE = CoreNode.MAX_USERNAME_SIZE;

    public final PublicKeyHash owner;
    public final Claim claim;
    private final Optional<byte[]> keyChangeProof;

    public UserPublicKeyLink(PublicKeyHash ownerHash, Claim claim, Optional<byte[]> keyChangeProof) {
        this.owner = ownerHash;
        this.claim = claim;
        this.keyChangeProof = keyChangeProof;
    }

    public UserPublicKeyLink(PublicKeyHash owner, Claim claim) {
        this(owner, claim, Optional.empty());
    }

    public Optional<byte[]> getKeyChangeProof() {
        return keyChangeProof.map(x -> Arrays.copyOfRange(x, 0, x.length));
    }

    public UserPublicKeyLink withClaim(Claim claim) {
        return new UserPublicKeyLink(owner, claim, keyChangeProof);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        UserPublicKeyLink that = (UserPublicKeyLink) o;

        if (owner != null ? !owner.equals(that.owner) : that.owner != null) return false;
        if (claim != null ? !claim.equals(that.claim) : that.claim != null) return false;
        return keyChangeProof.isPresent() ?
                that.keyChangeProof.isPresent() &&
                        Arrays.equals(keyChangeProof.get(), that.keyChangeProof.get()) :
                ! that.keyChangeProof.isPresent();
    }

    @Override
    public int hashCode() {
        int result = owner != null ? owner.hashCode() : 0;
        result = 31 * result + (claim != null ? claim.hashCode() : 0);
        result = 31 * result + keyChangeProof.map(Arrays::hashCode).orElse(0);
        return result;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> values = new TreeMap<>();
        values.put("owner", owner.toCbor());
        values.put("claim", claim.toCbor());
        keyChangeProof.ifPresent(proof -> values.put("keychange", new CborObject.CborByteArray(proof)));
        return CborObject.CborMap.build(values);
    }

    public static UserPublicKeyLink fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for UserPublicKeyLink: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;

        PublicKeyHash owner = PublicKeyHash.fromCbor(map.get("owner"));
        Claim claim  = Claim.fromCbor(map.get("claim"));
        Optional<byte[]> keyChangeProof = Optional.ofNullable(map.get("keychange"))
                .map(c -> ((CborObject.CborByteArray)c).value);
        return new UserPublicKeyLink(owner, claim, keyChangeProof);
    }

    public static CompletableFuture<List<UserPublicKeyLink>> createChain(SigningPrivateKeyAndPublicHash oldUser,
                                                                         SigningPrivateKeyAndPublicHash newUser,
                                                                         String username,
                                                                         LocalDate expiry,
                                                                         List<Multihash> storageProviders) {
        // sign new claim to username, with provided expiry
        return Claim.build(username, newUser.secret, expiry, storageProviders).thenCompose(newClaim ->
                // sign new key with old
                oldUser.secret.signMessage(newUser.publicKeyHash.serialize()).thenCompose(link -> {

                    // create link from old that never expires
                    return Claim.build(username, oldUser.secret, LocalDate.MAX, Collections.emptyList())
                            .thenApply(claim -> new UserPublicKeyLink(oldUser.publicKeyHash,
                                    claim,
                                    Optional.of(link))).thenApply(fromOld ->
                                    Arrays.asList(fromOld, new UserPublicKeyLink(newUser.publicKeyHash, newClaim)));
                }));
    }

    public static class Claim implements Cborable {
        public final String username;
        public final LocalDate expiry;
        // a list of storage-node ids
        public final List<Multihash> storageProviders;
        private final byte[] signedContents;

        public Claim(String username, LocalDate expiry, List<Multihash> storageProviders, byte[] signedContents) {
            this.username = username;
            this.expiry = expiry;
            this.storageProviders = storageProviders;

            this.signedContents = signedContents;
        }

        @Override
        public CborObject toCbor() {
            return new CborObject.CborList(Arrays.asList(
                    new CborObject.CborString(username),
                    new CborObject.CborString(expiry.toString()),
                    new CborObject.CborList(storageProviders.stream()
                            .map(id -> new CborObject.CborByteArray(id.toBytes()))
                            .collect(Collectors.toList())),
                    new CborObject.CborByteArray(signedContents)));
        }

        public static Claim fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborList))
                throw new IllegalStateException("Invalid cbor for Username claim: " + cbor);
            List<? extends Cborable> contents = ((CborObject.CborList) cbor).value;
            String username = ((CborObject.CborString) contents.get(0)).value;
            LocalDate expiry = LocalDate.parse(((CborObject.CborString) contents.get(1)).value);
            List<Multihash> storageProviders = ((CborObject.CborList)contents.get(2))
                    .value.stream()
                    .map(x -> Cid.cast(((CborObject.CborByteArray) x).value))
                    .collect(Collectors.toList());
            byte[] signedContents = ((CborObject.CborByteArray) contents.get(3)).value;
            return new Claim(username, expiry, storageProviders, signedContents);
        }

        public static CompletableFuture<Claim> build(String username, SecretSigningKey from, LocalDate expiryDate, List<Multihash> storageProviders) {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                Serialize.serialize(username, dout);
                Serialize.serialize(expiryDate.toString(), dout);
                dout.writeInt(storageProviders.size());
                for (Multihash storageProvider : storageProviders) {
                    Serialize.serialize(storageProvider.toBytes(), dout);
                }
                byte[] payload = bout.toByteArray();
                return from.signMessage(payload)
                        .thenApply(signed -> new Claim(username, expiryDate, storageProviders, signed));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public static CompletableFuture<Claim> deserialize(byte[] signedContents, PublicSigningKey signer) throws IOException {
            return signer.unsignMessage(signedContents).thenApply(contents -> {
                try {
                    ByteArrayInputStream bin = new ByteArrayInputStream(contents);
                    DataInputStream din = new DataInputStream(bin);
                    String username = Serialize.deserializeString(din, MAX_USERNAME_SIZE);
                    LocalDate expiry = LocalDate.parse(Serialize.deserializeString(din, 16));
                    int nStorageProviders = din.readInt();
                    List<Multihash> storageProviders = new ArrayList<>();
                    for (int i = 0; i < nStorageProviders; i++) {
                        storageProviders.add(Cid.cast(Serialize.deserializeByteArray(din, 100)));
                    }
                    return new Claim(username, expiry, storageProviders, signedContents);
                } catch(IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Claim that = (Claim) o;

            if (username != null ? !username.equals(that.username) : that.username != null) return false;
            if (expiry != null ? !expiry.equals(that.expiry) : that.expiry != null) return false;
            if (! storageProviders.equals(that.storageProviders))
                return false;
            return Arrays.equals(signedContents, that.signedContents);
        }

        @Override
        public int hashCode() {
            int result = username != null ? username.hashCode() : 0;
            result = 31 * result + (expiry != null ? expiry.hashCode() : 0);
            result = 31 * result + storageProviders.hashCode();
            result = 31 * result + Arrays.hashCode(signedContents);
            return result;
        }
    }

    public static CompletableFuture<List<UserPublicKeyLink>> createInitial(SigningPrivateKeyAndPublicHash signer,
                                                        String username,
                                                        LocalDate expiry,
                                                        List<Multihash> storageProviders) {
        return Claim.build(username, signer.secret, expiry, storageProviders)
                .thenApply(newClaim -> Collections.singletonList(new UserPublicKeyLink(signer.publicKeyHash, newClaim)));
    }

    public static CompletableFuture<List<UserPublicKeyLink>> merge(String username,
                                                                   List<UserPublicKeyLink> existing,
                                                                   List<UserPublicKeyLink> updated,
                                                                   ContentAddressedStorage ipfs) {
        return merge(username, existing, updated, ipfs, false);
    }

    /** A merge of chains read back, as validHistoricalChain checks them. */
    public static CompletableFuture<List<UserPublicKeyLink>> mergeHistorical(String username,
                                                                             List<UserPublicKeyLink> existing,
                                                                             List<UserPublicKeyLink> updated,
                                                                             ContentAddressedStorage ipfs) {
        return merge(username, existing, updated, ipfs, true);
    }

    private static CompletableFuture<List<UserPublicKeyLink>> merge(String username,
                                                                    List<UserPublicKeyLink> existing,
                                                                    List<UserPublicKeyLink> updated,
                                                                    ContentAddressedStorage ipfs,
                                                                    boolean historical) {
        if (updated.isEmpty())
            return Futures.errored(new IllegalStateException("Empty claim chain!"));
        if (updated.equals(existing))
            return CompletableFuture.completedFuture(updated);
        // nothing signs the key change proof of the final link
        if (updated.get(updated.size() - 1).getKeyChangeProof().isPresent())
            return Futures.errored(new IllegalStateException("Final link in claim chain has a key change proof!"));
        if (existing.isEmpty())
            return validChain(updated, username, ipfs, historical)
                    .thenApply(valid -> {
                        if (! valid)
                            throw new IllegalStateException("Invalid claim chain for " + username);
                        return updated;
                    });
        int indexOfChange = 0;
        for (int i=0; i < updated.size() && i < existing.size(); i++)
            if (updated.get(i).equals(existing.get(i)))
                indexOfChange++;
            else
                break;

        List<UserPublicKeyLink> tail = updated.subList(indexOfChange, updated.size());
        if (tail.isEmpty())
            return Futures.errored(new IllegalStateException("Claim chain is a prefix of the existing chain!"));

        UserPublicKeyLink currentLast = existing.get(existing.size() - 1);
        if (tail.get(0).claim.expiry.isBefore(currentLast.claim.expiry))
            throw new IllegalStateException("New claim chain expiry before existing!");

        if (! tail.get(0).owner.equals(currentLast.owner)) {
            if (tail.size() == 1)
                return Futures.errored(new IllegalStateException("User already exists: Invalid key change attempt!"));
            else
                return Futures.errored(new IllegalStateException("Different keys in merge chains intersection!"));
        }
        Set<PublicKeyHash> previousKeys = existing.stream()
                .limit(existing.size() - 1)
                .map(k -> k.owner)
                .collect(Collectors.toSet());
        if (previousKeys.contains(tail.get(tail.size() - 1).owner)) {
            // You cannot reuse a previous password
            return Futures.errored(new IllegalStateException("You cannot reuse a previous password!"));
        }
        List<UserPublicKeyLink> result = Stream.concat(
                existing.subList(0, existing.size() - 1).stream(),
                tail.stream())
                .collect(Collectors.toList());
        return validChain(result, username, ipfs, historical)
                .thenApply(valid -> {
                    if (! valid)
                        throw new IllegalStateException("Invalid key chain merge!");
                    return result;
                });
    }

    public static CompletableFuture<Boolean> validChain(List<UserPublicKeyLink> chain, String username, ContentAddressedStorage ipfs) {
        return validChain(chain, username, ipfs, false);
    }

    /** A chain read back, rather than written, may also have been signed by the key in an owner's hash before the
     *  identity rotated away from it in the routing log. A write must be signed by the current key, so a thief with
     *  the old key can't change the chain. */
    public static CompletableFuture<Boolean> validHistoricalChain(List<UserPublicKeyLink> chain, String username, ContentAddressedStorage ipfs) {
        return validChain(chain, username, ipfs, true);
    }

    private static CompletableFuture<Boolean> validChain(List<UserPublicKeyLink> chain, String username, ContentAddressedStorage ipfs, boolean historical) {
        List<CompletableFuture<Boolean>> validities = new ArrayList<>();
        for (int i=0; i < chain.size()-1; i++)
            validities.add(validLink(chain.get(i), chain.get(i + 1).owner, username, ipfs, historical));

        // last claim must have storage providers, earlier ones must be empty
        for (int i=0; i < chain.size(); i++) {
            if (i == chain.size() - 1) {
                if (chain.get(i).claim.storageProviders.size() != 1)
                    throw new IllegalStateException("More than 1 storage providers in claim for " + username);
            } else {
                if (! chain.get(i).claim.storageProviders.isEmpty())
                    throw new IllegalStateException("Non empty storage providers in old claim for " + username);
            }
        }
        BiFunction<Boolean, CompletableFuture<Boolean>, CompletableFuture<Boolean>> composer = (b, valid) -> valid.thenApply(res -> res && b);
        return Futures.reduceAll(validities,
                true,
                composer,
                (a, b) -> a && b)
                .thenCompose(valid -> {
                    if (!valid)
                        return CompletableFuture.completedFuture(false);
                    UserPublicKeyLink last = chain.get(chain.size() - 1);
                    return validClaim(last, username, ipfs, historical);
                });
    }

    private static CompletableFuture<List<PublicSigningKey>> ownerKeys(PublicKeyHash owner, ContentAddressedStorage ipfs, boolean historical) {
        return ipfs.getSigningKey(owner, owner).thenCompose(current -> {
            List<PublicSigningKey> keys = new ArrayList<>();
            current.ifPresent(keys::add);
            if (! historical)
                return Futures.of(keys);
            return ipfs.getInlineSigningKey(owner, owner).thenApply(inline -> {
                inline.filter(k -> ! keys.contains(k)).ifPresent(keys::add);
                return keys;
            });
        });
    }

    private static CompletableFuture<Boolean> anyVerifies(List<PublicSigningKey> keys, Function<PublicSigningKey, CompletableFuture<Boolean>> check) {
        if (keys.isEmpty())
            return Futures.of(false);
        return Futures.asyncExceptionally(() -> check.apply(keys.get(0)), t -> Futures.of(false))
                .thenCompose(valid -> valid ? Futures.of(true) : anyVerifies(keys.subList(1, keys.size()), check));
    }

    static CompletableFuture<Boolean> validLink(UserPublicKeyLink from,
                                                PublicKeyHash target,
                                                String username,
                                                ContentAddressedStorage ipfs) {
        return validLink(from, target, username, ipfs, false);
    }

    private static CompletableFuture<Boolean> validLink(UserPublicKeyLink from,
                                                        PublicKeyHash target,
                                                        String username,
                                                        ContentAddressedStorage ipfs,
                                                        boolean historical) {
        return validClaim(from, username, ipfs, historical).thenCompose(valid -> {
            if (!valid)
                return CompletableFuture.completedFuture(false);

            Optional<byte[]> keyChangeProof = from.getKeyChangeProof();
            if (!keyChangeProof.isPresent())
                return CompletableFuture.completedFuture(false);
            return ownerKeys(from.owner, ipfs, historical).thenCompose(keys -> anyVerifies(keys,
                    key -> key.unsignMessage(keyChangeProof.get()).thenApply(unsigned -> {
                        PublicKeyHash targetKey = PublicKeyHash.fromCbor(CborObject.fromByteArray(unsigned));
                        return Arrays.equals(targetKey.serialize(), target.serialize());
                    })));
        });
    }

    static CompletableFuture<Boolean> validClaim(UserPublicKeyLink from, String username, ContentAddressedStorage ipfs) {
        return validClaim(from, username, ipfs, false);
    }

    private static CompletableFuture<Boolean> validClaim(UserPublicKeyLink from, String username, ContentAddressedStorage ipfs, boolean historical) {
        if (username.contains(" ") || username.contains("\t") || username.contains("\n"))
            return CompletableFuture.completedFuture(false);
        if (username.length() > MAX_USERNAME_SIZE)
            return CompletableFuture.completedFuture(false);
        if (!from.claim.username.equals(username))
            return CompletableFuture.completedFuture(false);
        if (from.claim.storageProviders.size() > 1)
            return CompletableFuture.completedFuture(false);
        return ownerKeys(from.owner, ipfs, historical).thenCompose(keys -> anyVerifies(keys,
                key -> {
                    try {
                        return Claim.deserialize(from.claim.signedContents, key)
                                .thenApply(decoded -> from.claim.equals(decoded));
                    } catch (IOException e) {
                        return Futures.of(false);
                    }
                }));
    }

    public static boolean isExpiredClaim(UserPublicKeyLink from) {
        return from.claim.expiry.isBefore(LocalDate.now());
    }
}