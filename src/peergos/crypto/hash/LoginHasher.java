package peergos.crypto.hash;

public interface LoginHasher {

    byte[] hashToKeyBytes(String username, String password);
}
