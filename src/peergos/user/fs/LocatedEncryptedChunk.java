package peergos.user.fs;

public class LocatedEncryptedChunk {
    public final Location location;
    public final EncryptedChunk chunk;

    public LocatedEncryptedChunk(Location location, EncryptedChunk chunk) {
        this.location = location;
        this.chunk = chunk;
    }
}
