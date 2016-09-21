package peergos.shared.crypto.hash;

public interface LoginHasher {

    byte[] hashToKeyBytes(String username, String password);
}
