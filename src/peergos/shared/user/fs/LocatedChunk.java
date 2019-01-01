package peergos.shared.user.fs;

import peergos.shared.*;

public class LocatedChunk {
    public final Location location;
    public final MaybeMultihash existingHash;
    public final Chunk chunk;

    public LocatedChunk(Location location, MaybeMultihash existingHash, Chunk chunk) {
        this.location = location;
        this.existingHash = existingHash;
        this.chunk = chunk;
    }
}
