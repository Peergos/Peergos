package peergos.shared.corenode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
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

    public static List<UserPublicKeyLink> createChain(SigningPrivateKeyAndPublicHash oldUser,
                                                      SigningPrivateKeyAndPublicHash newUser,
                                                      String username,
                                                      LocalDate expiry,
                                                      List<Multihash> storageProviders) {
        // sign new claim to username, with provided expiry
        Claim newClaim = Claim.build(username, newUser.secret, expiry, storageProviders);

        // sign new key with old
        byte[] link = oldUser.secret.signMessage(newUser.publicKeyHash.serialize());

        // create link from old that never expires
        UserPublicKeyLink fromOld = new UserPublicKeyLink(oldUser.publicKeyHash,
                Claim.build(username, oldUser.secret, LocalDate.MAX, Collections.emptyList()),
                Optional.of(link));

        return Arrays.asList(fromOld, new UserPublicKeyLink(newUser.publicKeyHash, newClaim));
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

        public static Claim build(String username, SecretSigningKey from, LocalDate expiryDate, List<Multihash> storageProviders) {
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
                byte[] signed = from.signMessage(payload);
                return new Claim(username, expiryDate, storageProviders, signed);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public static Claim deserialize(byte[] signedContents, PublicSigningKey signer) throws IOException {
            byte[] contents = signer.unsignMessage(signedContents);
            ByteArrayInputStream bin = new ByteArrayInputStream(contents);
            DataInputStream din = new DataInputStream(bin);
            String username = Serialize.deserializeString(din, MAX_USERNAME_SIZE);
            LocalDate expiry = LocalDate.parse(Serialize.deserializeString(din, 16));
            int nStorageProviders = din.readInt();
            List<Multihash> storageProviders = new ArrayList<>();
            for (int i=0; i < nStorageProviders; i++) {
                storageProviders.add(Cid.cast(Serialize.deserializeByteArray(din, 100)));
            }
            return new Claim(username, expiry, storageProviders, signedContents);
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

    public static List<UserPublicKeyLink> createInitial(SigningPrivateKeyAndPublicHash signer,
                                                        String username,
                                                        LocalDate expiry,
                                                        List<Multihash> storageProviders) {
        Claim newClaim = Claim.build(username, signer.secret, expiry, storageProviders);

        return Collections.singletonList(new UserPublicKeyLink(signer.publicKeyHash, newClaim));
    }

    public static CompletableFuture<List<UserPublicKeyLink>> merge(List<UserPublicKeyLink> existing,
                                                                   List<UserPublicKeyLink> updated,
                                                                   ContentAddressedStorage ipfs) {
        if (existing.size() == 0 || updated.equals(existing))
            return CompletableFuture.completedFuture(updated);
        int indexOfChange = 0;
        for (int i=0; i < updated.size(); i++)
            if (updated.get(i).equals(existing.get(i)))
                indexOfChange++;
            else
                break;

        List<UserPublicKeyLink> tail = updated.subList(indexOfChange, updated.size());

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
        return validChain(result, tail.get(0).claim.username, ipfs)
                .thenApply(valid -> {
                    if (! valid)
                        throw new IllegalStateException("Invalid key chain merge!");
                    return result;
                });
    }

    public static CompletableFuture<Boolean> validChain(List<UserPublicKeyLink> chain, String username, ContentAddressedStorage ipfs) {
        List<CompletableFuture<Boolean>> validities = new ArrayList<>();
        for (int i=0; i < chain.size()-1; i++)
            validities.add(validLink(chain.get(i), chain.get(i + 1).owner, username, ipfs));

        BiFunction<Boolean, CompletableFuture<Boolean>, CompletableFuture<Boolean>> composer = (b, valid) -> valid.thenApply(res -> res && b);
        return Futures.reduceAll(validities,
                true,
                composer,
                (a, b) -> a && b)
                .thenCompose(valid -> {
                    if (!valid)
                        return CompletableFuture.completedFuture(false);
                    UserPublicKeyLink last = chain.get(chain.size() - 1);
                    return validClaim(last, username, ipfs);
                });
    }

    static CompletableFuture<Boolean> validLink(UserPublicKeyLink from,
                                                PublicKeyHash target,
                                                String username,
                                                ContentAddressedStorage ipfs) {
        return validClaim(from, username, ipfs).thenCompose(valid -> {
            if (!valid)
                return CompletableFuture.completedFuture(false);

            Optional<byte[]> keyChangeProof = from.getKeyChangeProof();
            if (!keyChangeProof.isPresent())
                return CompletableFuture.completedFuture(false);
            return ipfs.getSigningKey(from.owner).thenApply(ownerKeyOpt -> {
                if (!ownerKeyOpt.isPresent())
                    return false;
                PublicKeyHash targetKey = PublicKeyHash.fromCbor(CborObject.fromByteArray(ownerKeyOpt.get().unsignMessage(keyChangeProof.get())));
                if (!Arrays.equals(targetKey.serialize(), target.serialize()))
                    return false;

                return true;
            });
        });
    }

    static CompletableFuture<Boolean> validClaim(UserPublicKeyLink from, String username, ContentAddressedStorage ipfs) {
        if (username.contains(" ") || username.contains("\t") || username.contains("\n"))
            return CompletableFuture.completedFuture(false);
        if (username.length() > MAX_USERNAME_SIZE)
            return CompletableFuture.completedFuture(false);
        if (!from.claim.username.equals(username))
            return CompletableFuture.completedFuture(false);
        if (from.claim.storageProviders.size() > 1)
            return CompletableFuture.completedFuture(false);
        return ipfs.getSigningKey(from.owner).thenApply(ownerKeyOpt -> {
            if (!ownerKeyOpt.isPresent())
                return false;
            try {
                return from.claim.equals(Claim.deserialize(from.claim.signedContents, ownerKeyOpt.get()));
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        });
    }

    public static boolean isExpiredClaim(UserPublicKeyLink from) {
        return from.claim.expiry.isBefore(LocalDate.now());
    }
}