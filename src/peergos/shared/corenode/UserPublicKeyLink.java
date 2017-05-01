package peergos.shared.corenode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class UserPublicKeyLink implements Cborable{
    public static final int MAX_SIZE = 2*1024*1024;
    public static final int MAX_USERNAME_SIZE = 64;

    public final PublicSigningKey owner;
    public final UsernameClaim claim;
    private final Optional<byte[]> keyChangeProof;

    public UserPublicKeyLink(PublicSigningKey owner, UsernameClaim claim, Optional<byte[]> keyChangeProof) {
        this.owner = owner;
        this.claim = claim;
        this.keyChangeProof = keyChangeProof;
        // check validity of link
        if (keyChangeProof.isPresent()) {
            PublicSigningKey newKeys = PublicSigningKey.fromByteArray(owner.unsignMessage(keyChangeProof.get()));
        }
    }

    public UserPublicKeyLink(PublicSigningKey owner, UsernameClaim claim) {
        this(owner, claim, Optional.empty());
    }

    public Optional<byte[]> getKeyChangeProof() {
        return keyChangeProof.map(x -> Arrays.copyOfRange(x, 0, x.length));
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> values = new TreeMap<>();
        values.put("owner", owner.toCbor());
        values.put("claim", claim.toCbor());
        keyChangeProof.ifPresent(proof -> values.put("keychange", new CborObject.CborByteArray(proof)));
        return CborObject.CborMap.build(values);
    }

    public static UserPublicKeyLink fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for UserPublicKeyLink: " + cbor);
        SortedMap<CborObject, CborObject> values = ((CborObject.CborMap) cbor).values;
        PublicSigningKey owner = PublicSigningKey.fromCbor(values.get(new CborObject.CborString("owner")));
        UsernameClaim claim  = UsernameClaim.fromCbor(values.get(new CborObject.CborString("claim")));
        CborObject.CborString proofKey = new CborObject.CborString("keychange");
        Optional<byte[]> keyChangeProof = values.containsKey(proofKey) ?
                Optional.of(((CborObject.CborByteArray)values.get(proofKey)).value) : Optional.empty();
        return new UserPublicKeyLink(owner, claim, keyChangeProof);
    }

    @Deprecated
    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            Serialize.serialize(claim.toByteArray(), dout);
            dout.writeBoolean(keyChangeProof.isPresent());
            if (keyChangeProof.isPresent())
                Serialize.serialize(keyChangeProof.get(), dout);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        UserPublicKeyLink that = (UserPublicKeyLink) o;

        return Arrays.equals(toByteArray(), that.toByteArray());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(toByteArray());
    }

    @Deprecated
    public static UserPublicKeyLink fromByteArray(PublicSigningKey owner, byte[] raw) {
        try {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw));
            UsernameClaim proof = UsernameClaim.fromByteArray(owner, Serialize.deserializeByteArray(din, MAX_SIZE));
            boolean hasLink = din.readBoolean();
            Optional<byte[]> link = hasLink ? Optional.of(Serialize.deserializeByteArray(din, MAX_SIZE)) : Optional.empty();
            return new UserPublicKeyLink(owner, proof, link);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<UserPublicKeyLink> createChain(SigningKeyPair oldUser, SigningKeyPair newUser, String username, LocalDate expiry) {
        // sign new claim to username, with provided expiry
        UsernameClaim newClaim = UsernameClaim.create(username, newUser, expiry);

        // sign new key with old
        byte[] link = oldUser.signMessage(newUser.publicSigningKey.serialize());

        // create link from old that never expires
        UserPublicKeyLink fromOld = new UserPublicKeyLink(oldUser.publicSigningKey, UsernameClaim.create(username, oldUser, LocalDate.MAX), Optional.of(link));

        return Arrays.asList(fromOld, new UserPublicKeyLink(newUser.publicSigningKey, newClaim));
    }

    public static class UsernameClaim implements Cborable {
        public final String username;
        public final LocalDate expiry;
        private final byte[] signedContents;

        public UsernameClaim(String username, LocalDate expiry, byte[] signedContents) {
            this.username = username;
            this.expiry = expiry;
            this.signedContents = signedContents;
        }

        @Override
        public CborObject toCbor() {
            return new CborObject.CborList(Arrays.asList(new CborObject.CborString(username),
                    new CborObject.CborString(expiry.toString()),
                    new CborObject.CborByteArray(signedContents)));
        }

        public static UsernameClaim fromCbor(CborObject cbor) {
            if (! (cbor instanceof CborObject.CborList))
                throw new IllegalStateException("Invalid cbor for Username claim: " + cbor);
            String username = ((CborObject.CborString)((CborObject.CborList) cbor).value.get(0)).value;
            LocalDate expiry = LocalDate.parse(((CborObject.CborString)((CborObject.CborList) cbor).value.get(1)).value);
            byte[] signedContents = ((CborObject.CborByteArray)((CborObject.CborList) cbor).value.get(2)).value;
            return new UsernameClaim(username, expiry, signedContents);
        }

        @Deprecated
        public static UsernameClaim fromByteArray(PublicSigningKey from, byte[] raw) {
            try {
                DataInputStream rawdin = new DataInputStream(new ByteArrayInputStream(raw));
                byte[] signed = Serialize.deserializeByteArray(rawdin, MAX_SIZE);
                byte[] unsigned = from.unsignMessage(signed);
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(unsigned));
                String username = Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE);
                LocalDate expiry = LocalDate.parse(Serialize.deserializeString(din, 100));
                return new UsernameClaim(username, expiry, signed);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Deprecated
        public byte[] toByteArray() {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                Serialize.serialize(signedContents, dout);
                return bout.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public static UsernameClaim create(String username, SigningKeyPair from, LocalDate expiryDate) {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                Serialize.serialize(username, dout);
                Serialize.serialize(expiryDate.toString(), dout);
                byte[] payload = bout.toByteArray();
                byte[] signed = from.signMessage(payload);
                return new UsernameClaim(username, expiryDate, signed);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            UsernameClaim that = (UsernameClaim) o;
            return Arrays.equals(toByteArray(), that.toByteArray());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(toByteArray());
        }
    }

    public static List<UserPublicKeyLink> createInitial(SigningKeyPair signer, String username, LocalDate expiry) {
        UsernameClaim newClaim = UsernameClaim.create(username, signer, expiry);

        return Collections.singletonList(new UserPublicKeyLink(signer.publicSigningKey, newClaim));
    }

    public static List<UserPublicKeyLink> merge(List<UserPublicKeyLink> existing, List<UserPublicKeyLink> tail) {
        if (existing.size() == 0)
            return tail;
        if (!tail.get(0).owner.equals(existing.get(existing.size()-1).owner))
            throw new IllegalStateException("Different keys in merge chains intersection!");
        List<UserPublicKeyLink> result = Stream.concat(existing.subList(0, existing.size() - 1).stream(), tail.stream()).collect(Collectors.toList());
        validChain(result, tail.get(0).claim.username);
        return result;
    }

    public static void validChain(List<UserPublicKeyLink> chain, String username) {
        for (int i=0; i < chain.size()-1; i++)
            if (!validLink(chain.get(i), chain.get(i+1).owner, username))
                throw new IllegalStateException("Invalid public key chain link!");
        if (!validClaim(chain.get(chain.size()-1), username))
            throw new IllegalStateException("Invalid username claim!");
    }

    static boolean validLink(UserPublicKeyLink from, PublicSigningKey target, String username) {
        if (!validClaim(from, username))
            return true;

        Optional<byte[]> keyChangeProof = from.getKeyChangeProof();
        if (!keyChangeProof.isPresent())
            return false;
        PublicSigningKey targetKey = PublicSigningKey.fromByteArray(from.owner.unsignMessage(keyChangeProof.get()));
        if (!Arrays.equals(targetKey.serialize(), target.serialize()))
            return false;

        return true;
    }

    static boolean validClaim(UserPublicKeyLink from, String username) {
        if (username.contains(" ") || username.contains("\t") || username.contains("\n"))
            return false;
        if (username.length() > MAX_USERNAME_SIZE)
            return false;
        if (!from.claim.username.equals(username) || from.claim.expiry.isBefore(LocalDate.now()))
            return false;
        return true;
    }

}