package peergos.user.fs;

public class LocatedChunk {
    public final Location location;
    public final Chunk chunk;

    public LocatedChunk(Location location, Chunk chunk) {
        this.location = location;
        this.chunk = chunk;
    }
}
