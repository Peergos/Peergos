package peergos.shared.crypto.hash;

public class ScryptJS implements LoginHasher {

    public byte[] hashToKeyBytes(String username, String password) {
        return new byte[96];
    }
}
