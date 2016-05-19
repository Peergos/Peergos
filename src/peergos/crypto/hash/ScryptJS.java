package peergos.crypto.hash;

public class ScryptJS implements LoginHasher {

    native public byte[] hashToKeyBytes(String username, String password);
}
