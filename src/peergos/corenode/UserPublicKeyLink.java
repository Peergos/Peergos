package peergos.corenode;

import org.junit.*;
import peergos.crypto.*;
import peergos.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class UserPublicKeyLink {
    public static final int MAX_SIZE = 2*1024*1024;
    
    public final UsernameClaim claim;
    private final Optional<byte[]> keyChangeProof;

    public UserPublicKeyLink(UsernameClaim claim, Optional<byte[]> keyChangeProof) {
        this.claim = claim;
        this.keyChangeProof = keyChangeProof;
        // check validity of link
        if (keyChangeProof.isPresent()) {
            UserPublicKey newKeys = UserPublicKey.fromByteArray(claim.publicKey.unsignMessage(keyChangeProof.get()));
        }
    }

    public UserPublicKeyLink(UsernameClaim claim) {
        this(claim, Optional.empty());
    }

    public Optional<byte[]> getKeyChangeProof() {
        return keyChangeProof.map(x -> Arrays.copyOfRange(x, 0, x.length));
    }

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

    static List<UserPublicKeyLink> merge(List<UserPublicKeyLink> existing, List<UserPublicKeyLink> tail) {
        if (existing.size() == 0)
            return tail;
        if (tail.get(0).claim.publicKey.equals(existing.get(existing.size()-1).claim.publicKey))
            throw new IllegalStateException("Different keys in merge chains intersection!");
        List<UserPublicKeyLink> result = Stream.concat(existing.subList(0, existing.size() - 1).stream(), tail.stream()).collect(Collectors.toList());
        validChain(result, tail.get(0).claim.username);
        return result;
    }

    static void validChain(List<UserPublicKeyLink> chain, String username) {
        for (int i=0; i < chain.size()-1; i++)
            if (!validLink(chain.get(i), chain.get(i+1).claim.publicKey, username))
                throw new IllegalStateException("Invalid public key chain link!");
        if (!validClaim(chain.get(chain.size()-1), username))
            throw new IllegalStateException("Invalid username claim!");
    }

    static boolean validLink(UserPublicKeyLink from, UserPublicKey target, String username) {
        if (!validClaim(from, username))
            return true;

        Optional<byte[]> keyChangeProof = from.getKeyChangeProof();
        if (!keyChangeProof.isPresent())
            return false;
        UserPublicKey targetKey = UserPublicKey.fromByteArray(from.claim.publicKey.unsignMessage(keyChangeProof.get()));
        if (!targetKey.equals(target))
            return false;

        return true;
    }

    static boolean validClaim(UserPublicKeyLink from, String username) {
        if (username.contains(" ") || username.contains("\t") || username.contains("\n"))
            return false;
        if (!from.claim.username.equals(username) || from.claim.expiry.isBefore(LocalDate.now()))
            return false;
        return true;
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

        @Test
        public void coreNode() throws Exception {
            CoreNode core = CoreNode.getDefault();
            User user = User.random();
            String username = "someuser";

            // register the username
            UsernameClaim node = UsernameClaim.create(username, user, LocalDate.now().plusYears(2));
            UserPublicKeyLink upl = new UserPublicKeyLink(node);
            boolean success = core.updateChain(username, Arrays.asList(upl));
            List<UserPublicKeyLink> chain = core.getChain(username);
            if (chain.size() != 1 || !chain.get(0).equals(upl))
                throw new IllegalStateException("Retrieved chain element different "+chain +" != "+Arrays.asList(upl));

            // now change the expiry
            UsernameClaim node2 = UsernameClaim.create(username, user, LocalDate.now().plusYears(3));
            UserPublicKeyLink upl2 = new UserPublicKeyLink(node2);
            boolean success2 = core.updateChain(username, Arrays.asList(upl2));
            List<UserPublicKeyLink> chain2 = core.getChain(username);
            if (chain2.size() != 1 || !chain2.get(0).equals(upl2))
                throw new IllegalStateException("Retrieved chain element different "+chain2 +" != "+Arrays.asList(upl2));

            // now change the keys
            User user2 = User.random();
            List<UserPublicKeyLink> chain3 = UserPublicKeyLink.createChain(user, user2, username, LocalDate.now().plusWeeks(1));
            boolean success3 = core.updateChain(username, chain3);
            List<UserPublicKeyLink> chain3Retrieved = core.getChain(username);
            if (!chain3.equals(chain3Retrieved))
                throw new IllegalStateException("Retrieved chain element different");
        }
    }
}