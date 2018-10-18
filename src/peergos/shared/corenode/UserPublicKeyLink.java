package peergos.shared.corenode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class UserPublicKeyLink implements Cborable{
    public static final int MAX_SIZE = 2*1024*1024;
    public static final int MAX_USERNAME_SIZE = 64;

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
        Map<String, CborObject> values = new TreeMap<>();
        values.put("owner", owner.toCbor());
        values.put("claim", claim.toCbor());
        keyChangeProof.ifPresent(proof -> values.put("keychange", new CborObject.CborByteArray(proof)));
        return CborObject.CborMap.build(values);
    }

    public static UserPublicKeyLink fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for UserPublicKeyLink: " + cbor);
        SortedMap<CborObject, ? extends Cborable> values = ((CborObject.CborMap) cbor).values;
        PublicKeyHash owner = PublicKeyHash.fromCbor(values.get(new CborObject.CborString("owner")));
        Claim claim  = Claim.fromCbor(values.get(new CborObject.CborString("claim")));
        CborObject.CborString proofKey = new CborObject.CborString("keychange");
        Optional<byte[]> keyChangeProof = values.containsKey(proofKey) ?
                Optional.of(((CborObject.CborByteArray)values.get(proofKey)).value) : Optional.empty();
        return new UserPublicKeyLink(owner, claim, keyChangeProof);
    }

    public static List<UserPublicKeyLink> createChain(SigningPrivateKeyAndPublicHash oldUser,
                                                      SigningPrivateKeyAndPublicHash newUser,
                                                      String username,
                                                      LocalDate expiry,
                                                      List<Multihash> storageProviders) {
        // sign new claim to username, with provided expiry
        Claim newClaim = Claim.create(username, newUser.secret, expiry, storageProviders);

        // sign new key with old
        byte[] link = oldUser.secret.signMessage(newUser.publicKeyHash.serialize());

        // create link from old that never expires
        UserPublicKeyLink fromOld = new UserPublicKeyLink(oldUser.publicKeyHash,
                Claim.create(username, oldUser.secret, LocalDate.MAX, Collections.emptyList()),
                Optional.of(link));

        return Arrays.asList(fromOld, new UserPublicKeyLink(newUser.publicKeyHash, newClaim));
    }

    public static class Claim implements Cborable {
        public final String username;
        public final LocalDate expiry;
        public final List<Multihash> storageProviders;
        private final byte[] signedContents;

        public Claim(String username, LocalDate expiry, List<Multihash> storagePRoviders, byte[] signedContents) {
            this.username = username;
            this.expiry = expiry;
            this.storageProviders = storagePRoviders;
            this.signedContents = signedContents;
        }

        @Override
        public CborObject toCbor() {
            return new CborObject.CborList(Arrays.asList(
                    new CborObject.CborString(username),
                    new CborObject.CborString(expiry.toString()),
                    new CborObject.CborList(storageProviders.stream()
                            .map(CborObject.CborMerkleLink::new)
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
                    .map(x -> ((CborObject.CborMerkleLink)x).target)
                    .collect(Collectors.toList());
            byte[] signedContents = ((CborObject.CborByteArray) contents.get(3)).value;
            return new Claim(username, expiry, storageProviders, signedContents);
        }

        public static Claim create(String username, SecretSigningKey from, LocalDate expiryDate, List<Multihash> storageProviders) {
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
        Claim newClaim = Claim.create(username, signer.secret, expiry, storageProviders);

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
        if (! tail.get(0).owner.equals(existing.get(existing.size()-1).owner)) {
            CompletableFuture<List<UserPublicKeyLink>> err = new CompletableFuture<>();
            if (tail.size() == 1)
                err.completeExceptionally(new IllegalStateException("User already exists: Invalid key change attempt!"));
            else
                err.completeExceptionally(new IllegalStateException("Different keys in merge chains intersection!"));
            return err;
        }
        List<UserPublicKeyLink> result = Stream.concat(existing.subList(0, existing.size() - 1).stream(), tail.stream()).collect(Collectors.toList());
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
            validities.add(validLink(chain.get(i), chain.get(i+1).owner, username, ipfs));
        BiFunction<Boolean, CompletableFuture<Boolean>, CompletableFuture<Boolean>> composer = (b, valid) -> valid.thenApply(res -> res && b);
        return Futures.reduceAll(validities,
                true,
                composer,
                (a, b) -> a && b)
                .thenApply(valid -> {
                    if (!valid)
                        return valid;
                    UserPublicKeyLink last = chain.get(chain.size() - 1);
                    if (!validClaim(last, username)) {
                        return false;
                    }
                    return true;
                });
    }

    static CompletableFuture<Boolean> validLink(UserPublicKeyLink from,
                                                PublicKeyHash target,
                                                String username,
                                                ContentAddressedStorage ipfs) {
        if (!validClaim(from, username))
            return CompletableFuture.completedFuture(true);

        Optional<byte[]> keyChangeProof = from.getKeyChangeProof();
        if (!keyChangeProof.isPresent())
            return CompletableFuture.completedFuture(false);
        return ipfs.getSigningKey(from.owner).thenApply(ownerKeyOpt -> {
            if (! ownerKeyOpt.isPresent())
                return false;
            PublicKeyHash targetKey = PublicKeyHash.fromCbor(CborObject.fromByteArray(ownerKeyOpt.get().unsignMessage(keyChangeProof.get())));
            if (!Arrays.equals(targetKey.serialize(), target.serialize()))
                return false;

            return true;
        });
    }

    static boolean validClaim(UserPublicKeyLink from, String username) {
        if (username.contains(" ") || username.contains("\t") || username.contains("\n"))
            return false;
        if (username.length() > MAX_USERNAME_SIZE)
            return false;
        if (!from.claim.username.equals(username))
            return false;
        return true;
    }

    public static boolean isExpiredClaim(UserPublicKeyLink from) {
        return from.claim.expiry.isBefore(LocalDate.now());
    }
}