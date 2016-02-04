package peergos.corenode;

import org.ipfs.api.Multihash;
import org.junit.*;
import peergos.crypto.*;
import peergos.util.*;

import java.io.*;
import java.time.*;
import java.util.*;

public class UserPublicKeyLink {
    public static final int MAX_SIZE = 2*1024*1024;
    
    public final UserProof from;
    private final Optional<byte[]> targetProof;

    public UserPublicKeyLink(UserProof from, Optional<byte[]> targetProof) {
        this.from = from;
        this.targetProof = targetProof;
    }

    public UserPublicKeyLink(UserProof from) {
        this(from, Optional.empty());
    }

    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            Serialize.serialize(from.toByteArray(), dout);
            dout.writeBoolean(targetProof.isPresent());
            if (targetProof.isPresent())
                Serialize.serialize(targetProof.get(), dout);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static UserPublicKeyLink fromByteArray(byte[] raw) {
        try {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw));
            UserProof proof = UserProof.fromByteArray(Serialize.deserializeByteArray(din, MAX_SIZE));
            boolean hasLink = din.readBoolean();
            Optional<byte[]> link = hasLink ? Optional.of(Serialize.deserializeByteArray(din, MAX_SIZE)) : Optional.empty();
            return new UserPublicKeyLink(proof, link);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static class UserProof {
        public final UserPublicKey publicKey;
        public final String username;
        public final Multihash staticHash;
        public final LocalDate expiry;
        private final byte[] signedContents;

        public UserProof(UserPublicKey publicKey, String username, Multihash staticHash, LocalDate expiry, byte[] signedContents) {
            this.username = username;
            this.publicKey = publicKey;
            this.staticHash = staticHash;
            this.expiry = expiry;
            this.signedContents = signedContents;
        }

        public static UserProof fromByteArray(byte[] raw) {
            try {
                DataInputStream rawdin = new DataInputStream(new ByteArrayInputStream(raw));
                UserPublicKey from = UserPublicKey.deserialize(rawdin);
                byte[] signed = Serialize.deserializeByteArray(rawdin, MAX_SIZE);
                byte[] unsigned = from.unsignMessage(signed);
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(unsigned));
                String username = Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE);
                Multihash staticHash = new Multihash(Serialize.deserializeByteArray(din, 1024));
                LocalDate expiry = LocalDate.parse(Serialize.deserializeString(din, 100));
                return new UserProof(from, username, staticHash, expiry, signed);
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

        public static UserProof create(String username, User from, Multihash staticHash, LocalDate expiryDate) {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                Serialize.serialize(username, dout);
                Serialize.serialize(staticHash.toBytes(), dout);
                Serialize.serialize(expiryDate.toString(), dout);
                byte[] payload = bout.toByteArray();
                byte[] signed = from.signMessage(payload);
                return new UserProof(from.toUserPublicKey(), username, staticHash, expiryDate, signed);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class Tests {
        @Test
        public void create() {
            User user = User.random();
            UserProof node = UserProof.create("someuser", user, Multihash.fromBase58("QmZR5J83KkXbhUxofQR3pFKqiGtBcS3dRgny8wgf4r5FRY"), LocalDate.now().plusYears(2));
            UserPublicKeyLink upl = new UserPublicKeyLink(node);
            byte[] serialized1 = upl.toByteArray();
            UserPublicKeyLink upl2 = UserPublicKeyLink.fromByteArray(serialized1);
            byte[] serialized2 = upl2.toByteArray();
            if (!Arrays.equals(serialized1, serialized2))
                throw new IllegalStateException("toByteArray not inverse of fromByteArray!");
        }
    }
}