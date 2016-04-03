package peergos.user.fs;

public class LocatedEncryptedChunk {
    public final Location location;
    public final EncryptedChunk chunk;
    public final byte[] nonce;

    public LocatedEncryptedChunk(Location location, EncryptedChunk chunk, byte[] nonce) {
        this.location = location;
        this.chunk = chunk;
        this.nonce = nonce;
    }
}
