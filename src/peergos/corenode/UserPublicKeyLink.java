package peergos.corenode;

import org.junit.*;
import peergos.crypto.*;
import peergos.util.*;

import java.io.*;
import java.time.*;
import java.util.*;

public class UserPublicKeyLink {
    public static final int MAX_SIZE = 2*1024*1024;
    
    public final UsernameClaim from;
    private final Optional<byte[]> keyChangeProof;

    public UserPublicKeyLink(UsernameClaim from, Optional<byte[]> keyChangeProof) {
        this.from = from;
        this.keyChangeProof = keyChangeProof;
        // check validity of link
        if (keyChangeProof.isPresent()) {
            UserPublicKey newKeys = UserPublicKey.fromByteArray(from.publicKey.unsignMessage(keyChangeProof.get()));
        }
    }

    public UserPublicKeyLink(UsernameClaim from) {
        this(from, Optional.empty());
    }

    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            Serialize.serialize(from.toByteArray(), dout);
            dout.writeBoolean(keyChangeProof.isPresent());
            if (keyChangeProof.isPresent())
                Serialize.serialize(keyChangeProof.get(), dout);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static UserPublicKeyLink fromByteArray(byte[] raw) {
        try {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw));
            UsernameClaim proof = UsernameClaim.fromByteArray(Serialize.deserializeByteArray(din, MAX_SIZE));
            boolean hasLink = din.readBoolean();
            Optional<byte[]> link = hasLink ? Optional.of(Serialize.deserializeByteArray(din, MAX_SIZE)) : Optional.empty();
            return new UserPublicKeyLink(proof, link);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<UserPublicKeyLink> createChain(User oldUser, User newUser, String username, LocalDate expiry) {
        // sign new claim to username, with provided expiry
        UsernameClaim newClaim = UsernameClaim.create(username, newUser, expiry);

        // sign new keys with old
        byte[] link = oldUser.signMessage(newUser.toUserPublicKey().serialize());

        // create link from old that never expires
        UserPublicKeyLink fromOld = new UserPublicKeyLink(UsernameClaim.create(username, oldUser, LocalDate.MAX), Optional.of(link));

        return Arrays.asList(fromOld, new UserPublicKeyLink(newClaim));
    }

    public static class UsernameClaim {
        public final UserPublicKey publicKey;
        public final String username;
        public final LocalDate expiry;
        private final byte[] signedContents;

        public UsernameClaim(UserPublicKey publicKey, String username, LocalDate expiry, byte[] signedContents) {
            this.username = username;
            this.publicKey = publicKey;
            this.expiry = expiry;
            this.signedContents = signedContents;
        }

        public static UsernameClaim fromByteArray(byte[] raw) {
            try {
                DataInputStream rawdin = new DataInputStream(new ByteArrayInputStream(raw));
                UserPublicKey from = UserPublicKey.deserialize(rawdin);
                byte[] signed = Serialize.deserializeByteArray(rawdin, MAX_SIZE);
                byte[] unsigned = from.unsignMessage(signed);
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(unsigned));
                String username = Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE);
                LocalDate expiry = LocalDate.parse(Serialize.deserializeString(din, 100));
                return new UsernameClaim(from, username, expiry, signed);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public byte[] toByteArray() {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                publicKey.serialize(dout);
                Serialize.serialize(signedContents, dout);
                return bout.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public static UsernameClaim create(String username, User from, LocalDate expiryDate) {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                Serialize.serialize(username, dout);
                Serialize.serialize(expiryDate.toString(), dout);
                byte[] payload = bout.toByteArray();
                byte[] signed = from.signMessage(payload);
                return new UsernameClaim(from.toUserPublicKey(), username, expiryDate, signed);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class Tests {
        @Test
        public void createInitial() {
            User user = User.random();
            UsernameClaim node = UsernameClaim.create("someuser", user, LocalDate.now().plusYears(2));
            UserPublicKeyLink upl = new UserPublicKeyLink(node);
            testSerialization(upl);
        }

        public void testSerialization(UserPublicKeyLink link) {
            byte[] serialized1 = link.toByteArray();
            UserPublicKeyLink upl2 = UserPublicKeyLink.fromByteArray(serialized1);
            byte[] serialized2 = upl2.toByteArray();
            if (!Arrays.equals(serialized1, serialized2))
                throw new IllegalStateException("toByteArray not inverse of fromByteArray!");
        }

        @Test
        public void createChain() {
            User oldUser = User.random();
            User newUser = User.random();

            List<UserPublicKeyLink> links = UserPublicKeyLink.createChain(oldUser, newUser, "someuser", LocalDate.now().plusYears(2));
            links.forEach(link -> testSerialization(link));


        }
    }
}